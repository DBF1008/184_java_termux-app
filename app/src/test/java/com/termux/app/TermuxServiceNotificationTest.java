package com.termux.app;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Regression tests for the session-exit -> notification-update -> service-teardown chain in
 * {@link TermuxService}.
 *
 * <p>These guard against the defect where the foreground notification's session/task count became
 * inconsistent with the actual state when sessions ended, plugin commands were still awaiting their
 * results, or the service was manually stopped, and where a leftover notification could remain after
 * the service had left its foreground state.
 *
 * <p>The terminal/background shells themselves spawn native processes, which cannot run in a JVM unit
 * test, so the bookkeeping/teardown state machine is exercised directly via the shell manager lists
 * and the service callbacks rather than by spawning real shells.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class TermuxServiceNotificationTest {

    private Context context;
    private TermuxShellManager shellManager;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        TermuxShellManager.init(context);
        shellManager = TermuxShellManager.getShellManager();
        // The shell manager is a process-wide singleton, so clear it to isolate each test.
        shellManager.mTermuxSessions.clear();
        shellManager.mTermuxTasks.clear();
        shellManager.mPendingPluginExecutionCommands.clear();
    }

    private TermuxService createService() {
        return Robolectric.buildService(TermuxService.class).create().get();
    }

    /** Invoke the private {@link TermuxService#updateNotification()} that drives the state machine. */
    private static void invokeUpdateNotification(TermuxService service) throws Exception {
        Method method = TermuxService.class.getDeclaredMethod("updateNotification");
        method.setAccessible(true);
        method.invoke(service);
    }

    /** Read the private {@code mIsStoppingService} flag that guards against leftover notifications. */
    private static boolean getStoppingFlag(TermuxService service) throws Exception {
        Field field = TermuxService.class.getDeclaredField("mIsStoppingService");
        field.setAccessible(true);
        return field.getBoolean(service);
    }

    private static boolean isStoppedBySelf(TermuxService service) {
        return Shadows.shadowOf(service).isStoppedBySelf();
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Scenario 1: a normal terminal session. The notification count must reflect the running session,
     * and once the last session exits with nothing else running the service must tear itself down.
     */
    @Test
    public void testNormalTerminalSession_countIsConsistentAndServiceStopsWhenLastExits() throws Exception {
        TermuxService service = createService();

        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId());
        TermuxSession session = mock(TermuxSession.class);
        when(session.getExecutionCommand()).thenReturn(executionCommand);
        shellManager.mTermuxSessions.add(session);

        invokeUpdateNotification(service);

        // With one running session the service stays up and the notification shows the right count.
        Assert.assertFalse("Service must not stop while a session is running", isStoppedBySelf(service));
        Notification notification = Shadows.shadowOf(getNotificationManager())
            .getNotification(TermuxConstants.TERMUX_APP_NOTIFICATION_ID);
        Assert.assertNotNull("Foreground notification must be posted while a session is running", notification);
        Assert.assertEquals("1 session",
            Shadows.shadowOf(notification).getContentText().toString());

        // The session exits: it must be removed from the list and the service must tear down since
        // nothing else remains.
        service.onTermuxSessionExited(session);

        Assert.assertTrue("Session must be removed from the list once it exits",
            shellManager.mTermuxSessions.isEmpty());
        Assert.assertTrue("Service must request stop once the last session exits", isStoppedBySelf(service));
        Assert.assertTrue("Service must mark itself as stopping", getStoppingFlag(service));
    }

    /**
     * Scenario 2: a plugin command awaiting its result. While such a command is still pending the
     * service must not be torn down, otherwise the command would be dropped before it could be
     * processed. Once the command leaves the pending list the service may shut down.
     */
    @Test
    public void testPluginCommandAwaitingResult_isNotDroppedByNotificationUpdate() throws Exception {
        TermuxService service = createService();

        ExecutionCommand pendingCommand = new ExecutionCommand(TermuxShellManager.getNextShellId());
        pendingCommand.isPluginExecutionCommand = true;
        shellManager.mPendingPluginExecutionCommands.add(pendingCommand);

        // No sessions or tasks, but a plugin command is still pending, so the service must stay alive.
        invokeUpdateNotification(service);
        Assert.assertFalse("Service must not stop while a plugin command is pending", isStoppedBySelf(service));
        Assert.assertFalse("Service must not mark itself stopping while a plugin command is pending",
            getStoppingFlag(service));

        // The command resolves and leaves the pending list; now nothing remains, so the service stops.
        shellManager.mPendingPluginExecutionCommands.remove(pendingCommand);
        invokeUpdateNotification(service);
        Assert.assertTrue("Service must request stop once no sessions, tasks or pending commands remain",
            isStoppedBySelf(service));
        Assert.assertTrue("Service must mark itself as stopping", getStoppingFlag(service));
    }

    /**
     * Scenario 3: a manual service-stop. The service must leave foreground and request stop, and a
     * late notification update (e.g. from an interleaved exit callback) must not re-post the
     * notification, which would otherwise leave a leftover/incorrect notification behind.
     */
    @Test
    public void testManualServiceStop_doesNotLeaveLeftoverNotification() throws Exception {
        TermuxService service = createService();

        // Simulate a session present when the user stops the service. The mocked session does not
        // self-remove on kill, mirroring the window where teardown bookkeeping is still mid-flight.
        ExecutionCommand executionCommand = new ExecutionCommand(TermuxShellManager.getNextShellId());
        TermuxSession session = mock(TermuxSession.class);
        when(session.getExecutionCommand()).thenReturn(executionCommand);
        shellManager.mTermuxSessions.add(session);

        Intent stopIntent = new Intent(context, TermuxService.class).setAction(TERMUX_SERVICE.ACTION_STOP_SERVICE);
        service.onStartCommand(stopIntent, 0, 0);

        Assert.assertTrue("Manual stop must request the service to stop", isStoppedBySelf(service));
        Assert.assertTrue("Manual stop must mark the service as stopping", getStoppingFlag(service));

        // Clear any posted notifications, then trigger a late update. Because the service is stopping,
        // it must not re-post the notification even though a session object is still in the list.
        NotificationManager notificationManager = getNotificationManager();
        notificationManager.cancelAll();
        invokeUpdateNotification(service);
        Assert.assertNull("No leftover notification must be posted after the service has stopped",
            Shadows.shadowOf(notificationManager).getNotification(TermuxConstants.TERMUX_APP_NOTIFICATION_ID));
    }
}
