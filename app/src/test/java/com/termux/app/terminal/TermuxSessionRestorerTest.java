package com.termux.app.terminal;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Regression tests for {@link TermuxSessionRestorer}, the pure decision logic that keeps the
 * displayed/highlighted terminal session in sync with the stored "current" session across the
 * session creation, switching, recovery (activity rebuild) and removal links.
 * <p/>
 * These cover the reported defect where, with multiple sessions open, opening a new session via a
 * shortcut/external launch and then rebuilding the activity (screen rotation, theme reload) or
 * auto-closing a finished session left the foreground list highlight pointing at the wrong session.
 */
public class TermuxSessionRestorerTest {

    private static List<String> handles(String... handles) {
        return new ArrayList<>(Arrays.asList(handles));
    }

    // ---- getSessionHandleToRestore: activity rebuild (rotation, theme reload, shortcut/external launch) ----

    @Test
    public void restore_returnsNull_whenNoSessions() {
        Assert.assertNull(TermuxSessionRestorer.getSessionHandleToRestore(null, "a"));
        Assert.assertNull(TermuxSessionRestorer.getSessionHandleToRestore(Collections.emptyList(), "a"));
    }

    @Test
    public void restore_returnsStored_whenStoredStillRunning() {
        // After the activity is rebuilt, the explicitly selected session must be restored.
        Assert.assertEquals("b", TermuxSessionRestorer.getSessionHandleToRestore(handles("a", "b", "c"), "b"));
    }

    @Test
    public void restore_returnsStored_evenWhenItIsNotTheLast() {
        // Regression: the highlight must follow the *stored* session by handle and not simply default
        // to the last session. A session opened via a shortcut/external launch is persisted as the
        // current one; after a rotation we must return to it even though later sessions exist.
        Assert.assertEquals("a", TermuxSessionRestorer.getSessionHandleToRestore(handles("a", "b", "c"), "a"));
    }

    @Test
    public void restore_fallsBackToLast_whenStoredNoLongerRunning() {
        // The stored session has exited (e.g. an auto-closed background session) and is no longer in
        // the list, so fall back to the last running session.
        Assert.assertEquals("c", TermuxSessionRestorer.getSessionHandleToRestore(handles("a", "b", "c"), "gone"));
    }

    @Test
    public void restore_fallsBackToLast_whenNothingStored() {
        // First launch / nothing ever persisted.
        Assert.assertEquals("c", TermuxSessionRestorer.getSessionHandleToRestore(handles("a", "b", "c"), null));
    }

    @Test
    public void restore_singleSession() {
        Assert.assertEquals("only", TermuxSessionRestorer.getSessionHandleToRestore(handles("only"), null));
        Assert.assertEquals("only", TermuxSessionRestorer.getSessionHandleToRestore(handles("only"), "only"));
        Assert.assertEquals("only", TermuxSessionRestorer.getSessionHandleToRestore(handles("only"), "stale"));
    }

    // ---- getSessionHandleAfterRemoval: finished-session cleanup ----

    @Test
    public void removal_returnsNull_whenNoSessionsBefore() {
        Assert.assertNull(TermuxSessionRestorer.getSessionHandleAfterRemoval(null, "a", "a"));
        Assert.assertNull(TermuxSessionRestorer.getSessionHandleAfterRemoval(Collections.emptyList(), "a", "a"));
    }

    @Test
    public void removal_returnsNull_whenRemovingOnlySession() {
        // No sessions remain -> the activity should finish.
        Assert.assertNull(TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a"), "a", "a"));
    }

    @Test
    public void removal_keepsCurrent_whenNonCurrentSessionRemoved() {
        // A background session finishes and is auto-closed while the user is on another session.
        // The user must stay on their current session and not be yanked elsewhere.
        Assert.assertEquals("a", TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a", "b", "c"), "c", "a"));
    }

    @Test
    public void removal_keepsCurrent_whenEarlierNonCurrentSessionRemoved() {
        // Regression for index-based selection: an *earlier* background session is auto-closed.
        // Index-based logic would shift and land on the wrong session; handle-based keeps the user on
        // their current session.
        Assert.assertEquals("c", TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a", "b", "c"), "a", "c"));
    }

    @Test
    public void removal_selectsSlotSuccessor_whenCurrentRemovedInMiddle() {
        // The current session itself is removed; the session that took its slot becomes current.
        // remaining = [a, c]; removedIndex = 1 -> "c".
        Assert.assertEquals("c", TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a", "b", "c"), "b", "b"));
    }

    @Test
    public void removal_clampsToLast_whenCurrentRemovedAtEnd() {
        // The current (last) session is removed; clamp to the new last session.
        Assert.assertEquals("b", TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a", "b", "c"), "c", "c"));
    }

    @Test
    public void removal_selectsFirst_whenCurrentRemovedAtStart() {
        // remaining = [b, c]; removedIndex = 0 -> "b".
        Assert.assertEquals("b", TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a", "b", "c"), "a", "a"));
    }

    @Test
    public void removal_fallsBackToSlot_whenCurrentUnknown() {
        // currentHandle is null (unknown): treat it like the current slot was removed and pick the
        // session now occupying the removed index.
        Assert.assertEquals("c", TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a", "b", "c"), "b", null));
    }

    @Test
    public void removal_keepsCurrent_whenRemovedSessionAlreadyGone() {
        // The removed handle is no longer in the snapshot (already removed elsewhere) but the current
        // session is still present -> keep current.
        Assert.assertEquals("b", TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a", "b"), "gone", "b"));
    }

    @Test
    public void removal_fallsBackToLast_whenRemovedGoneAndCurrentGone() {
        // Neither the removed handle nor the current handle are present -> fall back to the last
        // remaining session.
        Assert.assertEquals("b", TermuxSessionRestorer.getSessionHandleAfterRemoval(handles("a", "b"), "gone", "alsoGone"));
    }

}
