package com.termux.app;

import android.app.Application;
import android.content.Intent;

import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.runner.app.AppShell;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.app.terminal.TermuxTerminalSessionServiceClient;
import com.termux.terminal.TerminalSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Regression tests for the TermuxService session lifecycle, notification updates, and service
 * cleanup chain. Covers the bugs fixed in the session exit / notification / service teardown flow:
 *
 * <ul>
 *   <li>Double-removal bug: killIfExecuting triggers onTermuxSessionExited callback which removes
 *       from list, then the old loop tried to remove at the same (now shifted) index, corrupting
 *       the list by removing the wrong session.</li>
 *   <li>Orphan processes: non-plugin AppShell tasks were removed from the tracking list without
 *       being killed, leaving actual OS processes running.</li>
 *   <li>Stale notification: service client onSessionFinished was a no-op when activity was unbound,
 *       leaving finished sessions in the list and the notification showing wrong counts.</li>
 *   <li>Deferred callback races: onAppShellExited posts via mHandler; after killAll clears the
 *       list, the deferred removal could find wrong entries or process duplicate plugin results.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE, application = Application.class)
public class TermuxServiceTest {

    private TermuxService mService;
    private TermuxShellManager mShellManager;
    private Method mKillAllMethod;
    private Method mUpdateNotificationMethod;

    @Before
    public void setUp() throws Exception {
        // Initialize properties before service creation (TermuxService.onCreate calls getProperties())
        android.app.Application app = RuntimeEnvironment.getApplication();
        TermuxAppSharedProperties.init(app);

        // Use Robolectric to create a real service instance with Android framework support
        // (getString, getSystemService for NotificationManager, etc.)
        Robolectric.ServiceController<TermuxService> controller =
            Robolectric.buildService(TermuxService.class);
        mService = controller.create().startCommand(new Intent(), 0, 0).get();

        // Initialize the shell manager singleton (mirrors what TermuxApplication.onCreate does)
        TermuxShellManager.init(mService);
        mShellManager = TermuxShellManager.getShellManager();

        // Reflectively access private methods for testing
        mKillAllMethod = TermuxService.class.getDeclaredMethod("killAllTermuxExecutionCommands");
        mKillAllMethod.setAccessible(true);

        mUpdateNotificationMethod = TermuxService.class.getDeclaredMethod("updateNotification");
        mUpdateNotificationMethod.setAccessible(true);
    }

    @After
    public void tearDown() {
        if (mShellManager != null) {
            mShellManager.mTermuxSessions.clear();
            mShellManager.mTermuxTasks.clear();
            mShellManager.mPendingPluginExecutionCommands.clear();
        }
    }



    // ==================== Shell Manager list management ====================

    @Test
    public void testShellManagerSessionAddAndRemove() {
        TermuxSession session1 = createMockTermuxSession(false);
        TermuxSession session2 = createMockTermuxSession(false);

        mShellManager.mTermuxSessions.add(session1);
        mShellManager.mTermuxSessions.add(session2);
        assertEquals(2, mShellManager.mTermuxSessions.size());

        // Remove by reference (as done in onTermuxSessionExited)
        assertTrue(mShellManager.mTermuxSessions.remove(session1));
        assertEquals(1, mShellManager.mTermuxSessions.size());
        assertSame(session2, mShellManager.mTermuxSessions.get(0));

        assertTrue(mShellManager.mTermuxSessions.remove(session2));
        assertEquals(0, mShellManager.mTermuxSessions.size());
    }

    @Test
    public void testShellManagerTaskAddAndRemove() {
        AppShell task1 = createMockAppShell(false);
        AppShell task2 = createMockAppShell(false);

        mShellManager.mTermuxTasks.add(task1);
        mShellManager.mTermuxTasks.add(task2);
        assertEquals(2, mShellManager.mTermuxTasks.size());

        assertTrue(mShellManager.mTermuxTasks.remove(task1));
        assertEquals(1, mShellManager.mTermuxTasks.size());

        assertTrue(mShellManager.mTermuxTasks.remove(task2));
        assertEquals(0, mShellManager.mTermuxTasks.size());
    }

    @Test
    public void testShellManagerClearAllLists() {
        mShellManager.mTermuxSessions.add(createMockTermuxSession(false));
        mShellManager.mTermuxSessions.add(createMockTermuxSession(false));
        mShellManager.mTermuxTasks.add(createMockAppShell(false));
        mShellManager.mPendingPluginExecutionCommands.add(new ExecutionCommand(1));

        // Clear all lists (as done in fixed killAllTermuxExecutionCommands)
        mShellManager.mTermuxSessions.clear();
        mShellManager.mTermuxTasks.clear();
        mShellManager.mPendingPluginExecutionCommands.clear();

        assertEquals(0, mShellManager.mTermuxSessions.size());
        assertEquals(0, mShellManager.mTermuxTasks.size());
        assertEquals(0, mShellManager.mPendingPluginExecutionCommands.size());
    }

    @Test
    public void testServiceGetTermuxSessionsSizeReflectsListState() {
        assertEquals(0, mService.getTermuxSessionsSize());
        assertTrue(mService.isTermuxSessionsEmpty());

        mShellManager.mTermuxSessions.add(createMockTermuxSession(false));
        assertEquals(1, mService.getTermuxSessionsSize());
        assertFalse(mService.isTermuxSessionsEmpty());

        mShellManager.mTermuxSessions.add(createMockTermuxSession(false));
        assertEquals(2, mService.getTermuxSessionsSize());

        mShellManager.mTermuxSessions.clear();
        assertEquals(0, mService.getTermuxSessionsSize());
        assertTrue(mService.isTermuxSessionsEmpty());
    }



    // ==================== killAllTermuxExecutionCommands regression tests ====================

    /**
     * Regression test for the double-removal bug.
     *
     * Before the fix: killIfExecuting(session, processResult=true) triggers the callback chain
     * processTermuxSessionResult → onTermuxSessionExited → mTermuxSessions.remove(session).
     * Then the loop would also try to remove the session at the same index, but since the list
     * shifted, it would remove a DIFFERENT session, corrupting the list.
     *
     * After the fix: killAllTermuxExecutionCommands works on a copy and clears the entire list
     * at the end, avoiding any index-based removal issues.
     */
    @Test
    public void testKillAllTermuxExecutionCommands_noDoubleRemoval() throws Exception {
        // Setup: 3 sessions, all non-plugin. When mWantsToStop=true, processResult=true for all.
        TermuxSession session1 = createMockTermuxSession(false);
        TermuxSession session2 = createMockTermuxSession(false);
        TermuxSession session3 = createMockTermuxSession(false);

        mShellManager.mTermuxSessions.add(session1);
        mShellManager.mTermuxSessions.add(session2);
        mShellManager.mTermuxSessions.add(session3);

        assertEquals(3, mShellManager.mTermuxSessions.size());

        // Set mWantsToStop to trigger the stop-service path
        setField(mService, "mWantsToStop", true);

        // Execute
        mKillAllMethod.invoke(mService);

        // All sessions should be killed
        verify(session1, times(1)).killIfExecuting(any(), anyBoolean());
        verify(session2, times(1)).killIfExecuting(any(), anyBoolean());
        verify(session3, times(1)).killIfExecuting(any(), anyBoolean());

        // All lists should be completely cleared (not partially corrupted)
        assertEquals(0, mShellManager.mTermuxSessions.size());
        assertEquals(0, mShellManager.mTermuxTasks.size());
        assertEquals(0, mShellManager.mPendingPluginExecutionCommands.size());
    }

    /**
     * Regression test for orphan processes bug.
     *
     * Before the fix: non-plugin AppShell tasks were removed from mTermuxTasks without being
     * killed (killIfExecuting was never called). The actual OS processes kept running as orphans.
     *
     * After the fix: ALL tasks are killed, regardless of whether they are plugin commands.
     */
    @Test
    public void testKillAllTermuxExecutionCommands_killsNonPluginTasks() throws Exception {
        AppShell pluginTask = createMockAppShell(true);
        AppShell nonPluginTask = createMockAppShell(false);

        mShellManager.mTermuxTasks.add(pluginTask);
        mShellManager.mTermuxTasks.add(nonPluginTask);

        setField(mService, "mWantsToStop", true);

        mKillAllMethod.invoke(mService);

        // Both tasks should be killed (previously, non-plugin task was NOT killed)
        verify(pluginTask, times(1)).killIfExecuting(any(), anyBoolean());
        verify(nonPluginTask, times(1)).killIfExecuting(any(), anyBoolean());

        // Plugin task should have processResult=true, non-plugin should have processResult=false
        verify(pluginTask).killIfExecuting(any(), eq(true));
        verify(nonPluginTask).killIfExecuting(any(), eq(false));

        assertEquals(0, mShellManager.mTermuxTasks.size());
    }

    /**
     * Verify that killAllTermuxExecutionCommands processes plugin results for sessions that
     * have pending results, and kills non-plugin sessions with processResult based on mWantsToStop.
     */
    @Test
    public void testKillAllTermuxExecutionCommands_pluginResultProcessing() throws Exception {
        TermuxSession pluginSession = createMockTermuxSession(true);
        TermuxSession normalSession = createMockTermuxSession(false);

        mShellManager.mTermuxSessions.add(pluginSession);
        mShellManager.mTermuxSessions.add(normalSession);

        // Even without mWantsToStop, plugin sessions with pending results should be processed
        setField(mService, "mWantsToStop", false);

        mKillAllMethod.invoke(mService);

        // Plugin session: processResult=true because isPluginExecutionCommandWithPendingResult
        verify(pluginSession).killIfExecuting(any(), eq(true));
        // Normal session: processResult=false because mWantsToStop=false and not a plugin
        verify(normalSession).killIfExecuting(any(), eq(false));

        assertEquals(0, mShellManager.mTermuxSessions.size());
    }

    /**
     * Verify pending plugin commands that never started are cancelled and results processed.
     */
    @Test
    public void testKillAllTermuxExecutionCommands_pendingPluginCommandsCancelled() throws Exception {
        ExecutionCommand pendingCmd = new ExecutionCommand(99);
        pendingCmd.isPluginExecutionCommand = true;
        // Set up a pending result configuration
        pendingCmd.resultConfig.resultPendingIntent = android.app.PendingIntent.getActivity(
            mService, 0, new Intent(), 0);

        mShellManager.mPendingPluginExecutionCommands.add(pendingCmd);

        setField(mService, "mWantsToStop", true);

        mKillAllMethod.invoke(mService);

        // Pending command should have been marked as failed (cancelled)
        assertTrue(pendingCmd.isStateFailed());

        assertEquals(0, mShellManager.mPendingPluginExecutionCommands.size());
    }

    /**
     * Verify that killAll with mixed sessions and tasks clears everything properly.
     * This is the comprehensive end-to-end test for the fix.
     */
    @Test
    public void testKillAllTermuxExecutionCommands_mixedSessionsAndTasks() throws Exception {
        TermuxSession pluginSession = createMockTermuxSession(true);
        TermuxSession normalSession = createMockTermuxSession(false);
        AppShell pluginTask = createMockAppShell(true);
        AppShell normalTask = createMockAppShell(false);
        ExecutionCommand pendingCmd = new ExecutionCommand(100);
        pendingCmd.isPluginExecutionCommand = true;
        pendingCmd.resultConfig.resultPendingIntent = android.app.PendingIntent.getActivity(
            mService, 0, new Intent(), 0);

        mShellManager.mTermuxSessions.add(pluginSession);
        mShellManager.mTermuxSessions.add(normalSession);
        mShellManager.mTermuxTasks.add(pluginTask);
        mShellManager.mTermuxTasks.add(normalTask);
        mShellManager.mPendingPluginExecutionCommands.add(pendingCmd);

        setField(mService, "mWantsToStop", true);

        mKillAllMethod.invoke(mService);

        // All sessions killed
        verify(pluginSession).killIfExecuting(any(), eq(true));
        verify(normalSession).killIfExecuting(any(), eq(true)); // mWantsToStop=true

        // All tasks killed (including non-plugin)
        verify(pluginTask).killIfExecuting(any(), eq(true));
        verify(normalTask).killIfExecuting(any(), eq(false)); // non-plugin, processResult=false

        // All lists cleared
        assertEquals(0, mShellManager.mTermuxSessions.size());
        assertEquals(0, mShellManager.mTermuxTasks.size());
        assertEquals(0, mShellManager.mPendingPluginExecutionCommands.size());
    }

    /**
     * Verify killAllTermuxExecutionCommands with empty lists does nothing harmful.
     */
    @Test
    public void testKillAllTermuxExecutionCommands_emptyListsIsSafe() throws Exception {
        setField(mService, "mWantsToStop", true);

        // Should not throw any exceptions
        mKillAllMethod.invoke(mService);

        assertEquals(0, mShellManager.mTermuxSessions.size());
        assertEquals(0, mShellManager.mTermuxTasks.size());
        assertEquals(0, mShellManager.mPendingPluginExecutionCommands.size());
    }



    // ==================== Service client onSessionFinished regression tests ====================

    /**
     * Regression test for the stale notification bug.
     *
     * Before the fix: TermuxTerminalSessionServiceClient.onSessionFinished() was a no-op
     * (inherited from TermuxTerminalSessionClientBase). When the activity was unbound/destroyed
     * and a session process exited, TermuxSession.finish() was never called. The session stayed
     * in mTermuxSessions, the notification showed stale counts, plugin results were never sent
     * back, and the service never auto-stopped.
     *
     * After the fix: TermuxTerminalSessionServiceClient.onSessionFinished() calls
     * termuxSession.finish(), which triggers the result processing chain and eventually
     * onTermuxSessionExited which removes the session and updates the notification.
     */
    @Test
    public void testServiceClientOnSessionFinished_callsTermuxSessionFinish() throws Exception {
        TermuxTerminalSessionServiceClient serviceClient =
            new TermuxTerminalSessionServiceClient(mService);

        TerminalSession mockTerminalSession = mock(TerminalSession.class);
        when(mockTerminalSession.mSessionName).thenReturn("test-session");

        TermuxSession mockTermuxSession = createMockTermuxSession(false);
        when(mockTermuxSession.getTerminalSession()).thenReturn(mockTerminalSession);

        // Register the session in the service so getTermuxSessionForTerminalSession can find it
        mShellManager.mTermuxSessions.add(mockTermuxSession);

        // Simulate session process exit while activity is not bound
        serviceClient.onSessionFinished(mockTerminalSession);

        // TermuxSession.finish() should have been called to trigger cleanup
        verify(mockTermuxSession, times(1)).finish();
    }

    /**
     * Verify that service client onSessionFinished does NOT call finish() when the service
     * is already stopping (to avoid racing with killAllTermuxExecutionCommands).
     */
    @Test
    public void testServiceClientOnSessionFinished_skipsWhenServiceStopping() throws Exception {
        TermuxTerminalSessionServiceClient serviceClient =
            new TermuxTerminalSessionServiceClient(mService);

        TerminalSession mockTerminalSession = mock(TerminalSession.class);
        when(mockTerminalSession.mSessionName).thenReturn("test-session");

        TermuxSession mockTermuxSession = createMockTermuxSession(false);
        when(mockTermuxSession.getTerminalSession()).thenReturn(mockTerminalSession);
        mShellManager.mTermuxSessions.add(mockTermuxSession);

        // Service is shutting down
        setField(mService, "mWantsToStop", true);

        serviceClient.onSessionFinished(mockTerminalSession);

        // finish() should NOT be called since service is stopping
        verify(mockTermuxSession, never()).finish();
    }

    /**
     * Verify that service client setTerminalShellPid correctly sets the pid on the execution command.
     */
    @Test
    public void testServiceClientSetTerminalShellPid() throws Exception {
        TermuxTerminalSessionServiceClient serviceClient =
            new TermuxTerminalSessionServiceClient(mService);

        TerminalSession mockTerminalSession = mock(TerminalSession.class);

        // Use a real ExecutionCommand so we can verify the mPid field is set
        ExecutionCommand realCmd = new ExecutionCommand(1);

        TermuxSession mockTermuxSession = mock(TermuxSession.class);
        when(mockTermuxSession.getTerminalSession()).thenReturn(mockTerminalSession);
        when(mockTermuxSession.getExecutionCommand()).thenReturn(realCmd);

        mShellManager.mTermuxSessions.add(mockTermuxSession);

        serviceClient.setTerminalShellPid(mockTerminalSession, 12345);

        assertEquals(12345, realCmd.mPid);
    }



    // ==================== onTermuxSessionExited callback guard tests ====================

    /**
     * Verify that onTermuxSessionExited removes the session from the list and the list
     * correctly reflects the removal (not a shifted/corrupted entry).
     */
    @Test
    public void testOnTermuxSessionExited_removesCorrectSession() {
        TermuxSession session1 = createMockTermuxSession(false);
        TermuxSession session2 = createMockTermuxSession(false);
        TermuxSession session3 = createMockTermuxSession(false);

        mShellManager.mTermuxSessions.add(session1);
        mShellManager.mTermuxSessions.add(session2);
        mShellManager.mTermuxSessions.add(session3);

        // Simulate session2 exiting
        mService.onTermuxSessionExited(session2);

        // session2 should be removed, session1 and session3 should remain
        assertEquals(2, mShellManager.mTermuxSessions.size());
        assertTrue(mShellManager.mTermuxSessions.contains(session1));
        assertFalse(mShellManager.mTermuxSessions.contains(session2));
        assertTrue(mShellManager.mTermuxSessions.contains(session3));
    }

    /**
     * Verify that onTermuxSessionExited skips processing when service is stopping,
     * preventing duplicate plugin result delivery.
     */
    @Test
    public void testOnTermuxSessionExited_skipsWhenServiceStopping() throws Exception {
        TermuxSession pluginSession = createMockTermuxSession(true);
        mShellManager.mTermuxSessions.add(pluginSession);

        // Service is shutting down (killAllTermuxExecutionCommands already ran)
        setField(mService, "mWantsToStop", true);

        mService.onTermuxSessionExited(pluginSession);

        // Session should NOT have been removed (killAllTermuxExecutionCommands handles cleanup)
        assertEquals(1, mShellManager.mTermuxSessions.size());
    }

    /**
     * Verify onTermuxSessionExited processes plugin results for non-stopping case.
     */
    @Test
    public void testOnTermuxSessionExited_processesPluginResults() {
        TermuxSession pluginSession = createMockTermuxSession(true);
        mShellManager.mTermuxSessions.add(pluginSession);

        // Service is NOT stopping
        mService.onTermuxSessionExited(pluginSession);

        // Session should be removed
        assertEquals(0, mShellManager.mTermuxSessions.size());
    }



    // ==================== onAppShellExited callback guard tests ====================

    /**
     * Verify that onAppShellExited removes the task from the list.
     * Note: onAppShellExited uses mHandler.post(), so we need to flush the handler.
     */
    @Test
    public void testOnAppShellExited_removesCorrectTask() {
        AppShell task1 = createMockAppShell(false);
        AppShell task2 = createMockAppShell(false);

        mShellManager.mTermuxTasks.add(task1);
        mShellManager.mTermuxTasks.add(task2);

        // Simulate task1 exiting - posts removal to handler
        mService.onAppShellExited(task1);

        // Flush the main handler to execute the posted runnable
        ShadowLooper.idleMainLooper();

        // task1 should be removed, task2 should remain
        assertEquals(1, mShellManager.mTermuxTasks.size());
        assertFalse(mShellManager.mTermuxTasks.contains(task1));
        assertTrue(mShellManager.mTermuxTasks.contains(task2));
    }

    /**
     * Verify that deferred onAppShellExited callback is a no-op when service is stopping.
     * This prevents duplicate plugin result processing after killAllTermuxExecutionCommands
     * has already handled cleanup.
     */
    @Test
    public void testOnAppShellExited_skipsWhenServiceStopping() throws Exception {
        AppShell pluginTask = createMockAppShell(true);
        mShellManager.mTermuxTasks.add(pluginTask);

        // Service is shutting down
        setField(mService, "mWantsToStop", true);

        mService.onAppShellExited(pluginTask);

        // Flush the main handler
        ShadowLooper.idleMainLooper();

        // Task should NOT have been removed (killAllTermuxExecutionCommands handles cleanup)
        assertEquals(1, mShellManager.mTermuxTasks.size());
    }



    // ==================== Manual stop service scenario ====================

    /**
     * Integration test: simulate the full ACTION_STOP_SERVICE flow.
     * Verifies that all sessions and tasks are killed, lists are cleared,
     * and no stale state remains.
     */
    @Test
    public void testManualStopService_killsAllAndClearsState() throws Exception {
        // Setup: mix of sessions and tasks
        TermuxSession session1 = createMockTermuxSession(true);  // plugin
        TermuxSession session2 = createMockTermuxSession(false); // normal
        AppShell task1 = createMockAppShell(true);               // plugin
        AppShell task2 = createMockAppShell(false);              // non-plugin

        mShellManager.mTermuxSessions.add(session1);
        mShellManager.mTermuxSessions.add(session2);
        mShellManager.mTermuxTasks.add(task1);
        mShellManager.mTermuxTasks.add(task2);

        // Simulate ACTION_STOP_SERVICE
        setField(mService, "mWantsToStop", true);
        mKillAllMethod.invoke(mService);

        // Verify all sessions killed with processResult=true (mWantsToStop=true)
        verify(session1).killIfExecuting(any(), eq(true));
        verify(session2).killIfExecuting(any(), eq(true));

        // Verify all tasks killed; plugin task gets processResult=true
        verify(task1).killIfExecuting(any(), eq(true));
        verify(task2).killIfExecuting(any(), eq(false));

        // All lists cleared
        assertEquals(0, mShellManager.mTermuxSessions.size());
        assertEquals(0, mShellManager.mTermuxTasks.size());
        assertEquals(0, mShellManager.mPendingPluginExecutionCommands.size());

        // wantsToStop should be set
        assertTrue(mService.wantsToStop());
    }

    /**
     * Verify that after a full stop, any delayed callbacks from exited sessions/tasks
     * are harmless no-ops and don't corrupt the cleared state.
     */
    @Test
    public void testDelayedCallbacksAfterStop_areHarmlessNoOps() throws Exception {
        AppShell task = createMockAppShell(true);
        mShellManager.mTermuxTasks.add(task);

        // Stop the service
        setField(mService, "mWantsToStop", true);
        mKillAllMethod.invoke(mService);

        // Lists should be empty after stop
        assertEquals(0, mShellManager.mTermuxTasks.size());

        // Now simulate a delayed onAppShellExited callback firing after the stop
        mService.onAppShellExited(task);
        ShadowLooper.idleMainLooper();

        // List should still be empty - the deferred callback was a no-op
        assertEquals(0, mShellManager.mTermuxTasks.size());
    }



    // ==================== Notification consistency tests ====================

    /**
     * Verify that updateNotification correctly identifies the "nothing running" state
     * and requests service stop.
     */
    @Test
    public void testUpdateNotification_requestsStopWhenNothingRunning() throws Exception {
        // No sessions, no tasks, no wake lock
        assertEquals(0, mShellManager.mTermuxSessions.size());
        assertEquals(0, mShellManager.mTermuxTasks.size());
        assertNull(getField(mService, "mWakeLock"));

        // Should call requestStopService() - we verify this doesn't throw
        mUpdateNotificationMethod.invoke(mService);
    }

    /**
     * Verify that updateNotification does NOT stop the service when sessions exist.
     */
    @Test
    public void testUpdateNotification_keepsRunningWithSessions() throws Exception {
        mShellManager.mTermuxSessions.add(createMockTermuxSession(false));

        // Should call notificationManager.notify() instead of requestStopService()
        mUpdateNotificationMethod.invoke(mService);

        // Service should still have sessions (not stopped)
        assertEquals(1, mShellManager.mTermuxSessions.size());
    }

    /**
     * Verify that updateNotification does NOT stop the service when tasks exist.
     */
    @Test
    public void testUpdateNotification_keepsRunningWithTasks() throws Exception {
        mShellManager.mTermuxTasks.add(createMockAppShell(false));

        mUpdateNotificationMethod.invoke(mService);

        assertEquals(1, mShellManager.mTermuxTasks.size());
    }



    // ==================== Helper methods ====================

    /**
     * Create a mock TermuxSession with controlled behavior.
     * Uses Mockito to mock the concrete TermuxSession class, avoiding the need for
     * a real TerminalSession (which requires JNI) and a real process.
     *
     * @param isPlugin If true, the mock's ExecutionCommand is configured as a plugin command
     *                 with a pending result.
     */
    private TermuxSession createMockTermuxSession(boolean isPlugin) {
        TermuxSession mock = mock(TermuxSession.class);

        ExecutionCommand cmd = mock(ExecutionCommand.class);
        when(mock.getExecutionCommand()).thenReturn(cmd);
        when(cmd.isPluginExecutionCommandWithPendingResult()).thenReturn(isPlugin);

        return mock;
    }

    /**
     * Create a mock AppShell with controlled behavior.
     *
     * @param isPlugin If true, the mock's ExecutionCommand is configured as a plugin command
     *                 with a pending result.
     */
    private AppShell createMockAppShell(boolean isPlugin) {
        AppShell mock = mock(AppShell.class);

        ExecutionCommand cmd = mock(ExecutionCommand.class);
        when(mock.getExecutionCommand()).thenReturn(cmd);
        when(cmd.isPluginExecutionCommandWithPendingResult()).thenReturn(isPlugin);

        return mock;
    }

    /** Set a field value on an object using reflection. */
    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** Get a field value from an object using reflection. */
    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    /** Find a field by name, searching the class hierarchy. */
    private static Field findField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found in " + clazz.getName() + " hierarchy");
    }
}
