package com.termux.app.terminal;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure (framework independent) decisions for which terminal session should become the "current"
 * session across the session creation, switching, recovery (activity rebuild) and removal links.
 * <p/>
 * Sessions are identified by their {@code TerminalSession.mHandle} so that decisions stay valid even
 * when the underlying list is rebuilt (e.g. after a screen rotation, theme reload or when the
 * activity is recreated for a shortcut/external launch) where positional state restored by the
 * framework would otherwise drift away from the actually selected session.
 * <p/>
 * Kept dependency free on purpose so the behaviour can be unit tested without the Android framework.
 */
public final class TermuxSessionRestorer {

    private TermuxSessionRestorer() {}

    /**
     * Pick the handle of the session that should become current after the activity is rebuilt.
     *
     * @param sessionHandles The handles of the currently running sessions, in display order.
     * @param storedHandle The handle persisted as the current session, may be {@code null}.
     * @return The stored handle if it still references a running session, otherwise the last running
     * session, or {@code null} if there are no sessions.
     */
    @Nullable
    public static String getSessionHandleToRestore(@Nullable List<String> sessionHandles, @Nullable String storedHandle) {
        if (sessionHandles == null || sessionHandles.isEmpty()) return null;

        // Prefer the explicitly stored session if it is still running.
        if (storedHandle != null && sessionHandles.contains(storedHandle))
            return storedHandle;

        // Otherwise fall back to the last running session.
        return sessionHandles.get(sessionHandles.size() - 1);
    }

    /**
     * Pick the handle of the session that should become current after {@code removedHandle} is removed.
     * <p/>
     * If the removed session was not the current one (e.g. a finished background session that was
     * auto-closed), the user is kept on their current session. If the current session itself was
     * removed, the session that took its slot is selected, or the last session if it was at the end.
     *
     * @param handlesBeforeRemoval The handles of the running sessions, in display order, captured
     *                             before the removal.
     * @param removedHandle The handle of the session being removed.
     * @param currentHandle The handle of the session that is currently selected, may be {@code null}.
     * @return The handle of the session to make current, or {@code null} if no sessions remain.
     */
    @Nullable
    public static String getSessionHandleAfterRemoval(@Nullable List<String> handlesBeforeRemoval,
                                                      @Nullable String removedHandle,
                                                      @Nullable String currentHandle) {
        if (handlesBeforeRemoval == null || handlesBeforeRemoval.isEmpty()) return null;

        List<String> remaining = new ArrayList<>(handlesBeforeRemoval);
        int removedIndex = remaining.indexOf(removedHandle);
        if (removedIndex >= 0)
            remaining.remove(removedIndex);

        if (remaining.isEmpty()) return null;

        // The removed session was not the current one, so keep the user on their current session.
        if (currentHandle != null && !currentHandle.equals(removedHandle) && remaining.contains(currentHandle))
            return currentHandle;

        // The current session was removed (or was unknown): switch to the session that took its
        // slot, clamping to the last session if the removed one was at the end.
        int newIndex = removedIndex < 0 ? remaining.size() - 1 : removedIndex;
        if (newIndex >= remaining.size()) newIndex = remaining.size() - 1;
        if (newIndex < 0) newIndex = 0;
        return remaining.get(newIndex);
    }

}
