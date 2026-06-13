package com.termux.app.terminal;

import android.app.Service;

import androidx.annotation.NonNull;

import com.termux.app.TermuxService;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class TermuxTerminalSessionServiceClient extends TermuxTerminalSessionClientBase {

    private static final String LOG_TAG = "TermuxTerminalSessionServiceClient";

    private final TermuxService mService;

    public TermuxTerminalSessionServiceClient(TermuxService service) {
        this.mService = service;
    }

    /**
     * Called when a {@link TerminalSession} process exits while the activity is not bound to the service.
     *
     * When the activity client is bound, it handles session cleanup in its own {@code onSessionFinished}
     * (including UI decisions like auto-removing sessions with specific exit codes). But when the activity
     * is not bound, this service-side client is used instead. Without this override, the base class no-op
     * would leave the finished session in the session list, keep the notification count stale, prevent
     * plugin results from being sent back, and block the service from auto-stopping when no sessions remain.
     *
     * Calling {@link TermuxSession#finish()} triggers the result processing chain which invokes
     * {@link TermuxService#onTermuxSessionExited(TermuxSession)}, which removes the session from the list,
     * processes any pending plugin results, and updates the foreground notification.
     */
    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        if (mService.wantsToStop()) {
            // Service is already shutting down via ACTION_STOP_SERVICE; killAllTermuxExecutionCommands
            // will handle cleanup. Avoid redundant finish() calls that could race with it.
            return;
        }

        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(finishedSession);
        if (termuxSession != null) {
            Logger.logDebug(LOG_TAG, "onSessionFinished: finishing \"" + finishedSession.mSessionName + "\" session (activity not bound)");
            termuxSession.finish();
        }
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }

}
