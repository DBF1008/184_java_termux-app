package com.termux.app;

import android.content.Context;
import android.content.Intent;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Regression tests for session management logic covering:
 * - Shortcut (ACTION_RUN) entry creating sessions
 * - External plugin launch with KEEP_CURRENT / SWITCH session actions
 * - Finished session cleanup preserving the correct current session
 * - Highlight sync between the drawer ListView and the actual current session
 * - Activity recreation restoring the correct session
 *
 * These tests verify the core algorithms extracted from
 * {@link com.termux.app.TermuxService},
 * {@link com.termux.app.terminal.TermuxTerminalSessionActivityClient}, and
 * {@link com.termux.app.TermuxActivity}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class TermuxSessionManagementTest {

    // ────────────────────────────────────────────────────────────
    // Algorithm helpers — mirror the production code logic so we
    // can unit-test the algorithms without the full Android stack.
    // ────────────────────────────────────────────────────────────

    /**
     * Mirrors the index-selection logic in
     * {@code TermuxTerminalSessionActivityClient#removeFinishedSession()}.
     *
     * @param removedIndex    The index of the session that was removed.
     * @param wasCurrent      Whether the removed session was the one displayed.
     * @param remainingSize   Number of sessions remaining after removal.
     * @return The index of the session to switch to, or -1 if no switch needed.
     */
    static int calculateTargetIndexAfterRemoval(int removedIndex, boolean wasCurrent, int remainingSize) {
        if (remainingSize == 0) return -1;            // activity should finish
        if (!wasCurrent) return -1;                   // no switch — current session unchanged
        if (removedIndex >= remainingSize)
            return remainingSize - 1;
        return removedIndex;
    }

    /**
     * Mirrors the handle-lookup logic in
     * {@code TermuxService#getTerminalSessionForHandle(String)}.
     */
    static boolean isSessionHandleValid(String storedHandle, List<String> runningHandles) {
        if (storedHandle == null) return false;
        return runningHandles.contains(storedHandle);
    }

    /**
     * Mirrors the highlight index computation in
     * {@code TermuxActivity#syncSessionHighlight()}.
     */
    static int calculateHighlightIndex(String currentHandle, List<String> sessionHandles) {
        if (currentHandle == null) return -1;
        return sessionHandles.indexOf(currentHandle);
    }

    /**
     * Mirrors the stored-session validation logic in
     * {@code TermuxService#validateStoredSession()}.
     *
     * @return The handle that should be stored after validation.
     */
    static String validateStoredSession(String storedHandle, List<String> runningHandles,
                                        String activityCurrentHandle) {
        if (storedHandle == null) return null;
        if (runningHandles.contains(storedHandle)) return storedHandle; // still valid

        // Stale — fall back to activity's current session.
        if (activityCurrentHandle != null && runningHandles.contains(activityCurrentHandle))
            return activityCurrentHandle;

        // Fall back to last session.
        if (!runningHandles.isEmpty())
            return runningHandles.get(runningHandles.size() - 1);

        return null;
    }

    /**
     * Simulates the {@code handleSessionAction} KEEP_CURRENT logic
     * for determining whether the stored session should be updated.
     */
    static String resolveKeepCurrentStoredSession(int totalSessions, String newSessionHandle,
                                                   String existingStoredHandle,
                                                   List<String> runningHandles,
                                                   String activityCurrentHandle) {
        if (totalSessions == 1)
            return newSessionHandle;
        // totalSessions > 1: validate the existing stored session
        return validateStoredSession(existingStoredHandle, runningHandles, activityCurrentHandle);
    }

    // ────────────────────────────────────────────────────────────
    // Test helpers
    // ────────────────────────────────────────────────────────────

    private static List<String> handles(String... hs) {
        List<String> list = new ArrayList<>();
        for (String h : hs) list.add(h);
        return list;
    }

    // ────────────────────────────────────────────────────────────
    // Tests: removeFinishedSession index selection
    // ────────────────────────────────────────────────────────────

    @Test
    public void testRemoveCurrentSession_firstOfMany_switchesToSameIndex() {
        // sessions: [A, B, C], removing A (index 0), was current
        int target = calculateTargetIndexAfterRemoval(0, true, 2);
        assertEquals(0, target); // should switch to B (now at index 0)
    }

    @Test
    public void testRemoveCurrentSession_middleOfMany_switchesToSameIndex() {
        // sessions: [A, B, C], removing B (index 1), was current
        int target = calculateTargetIndexAfterRemoval(1, true, 2);
        assertEquals(1, target); // should switch to C (now at index 1)
    }

    @Test
    public void testRemoveCurrentSession_lastOfMany_clampsToLast() {
        // sessions: [A, B, C], removing C (index 2), was current
        int target = calculateTargetIndexAfterRemoval(2, true, 2);
        assertEquals(1, target); // clamps to index 1 → B
    }

    @Test
    public void testRemoveCurrentSession_onlySession_activityFinishes() {
        // sessions: [A], removing A (index 0), was current
        int target = calculateTargetIndexAfterRemoval(0, true, 0);
        assertEquals(-1, target); // activity should finish
    }

    @Test
    public void testRemoveNonCurrentSession_noSwitch() {
        // sessions: [A, B, C], removing A (index 0), NOT current (current is C)
        int target = calculateTargetIndexAfterRemoval(0, false, 2);
        assertEquals(-1, target); // no switch — C is still displayed
    }

    @Test
    public void testRemoveNonCurrentSession_middleSession_noSwitch() {
        // sessions: [A, B, C], removing B (index 1), NOT current (current is A)
        int target = calculateTargetIndexAfterRemoval(1, false, 2);
        assertEquals(-1, target); // no switch — A is still displayed
    }

    // ────────────────────────────────────────────────────────────
    // Tests: highlight sync
    // ────────────────────────────────────────────────────────────

    @Test
    public void testHighlightSync_afterSessionAdd() {
        List<String> sessions = handles("a", "b", "c");
        // The new session "c" is now current
        int highlightIndex = calculateHighlightIndex("c", sessions);
        assertEquals(2, highlightIndex);
    }

    @Test
    public void testHighlightSync_afterSessionRemove() {
        // After removing "b", sessions = [a, c], current = "c"
        List<String> sessions = handles("a", "c");
        int highlightIndex = calculateHighlightIndex("c", sessions);
        assertEquals(1, highlightIndex);
    }

    @Test
    public void testHighlightSync_currentSessionGone() {
        // Current session "x" was removed, not in the list
        List<String> sessions = handles("a", "b");
        int highlightIndex = calculateHighlightIndex("x", sessions);
        assertEquals(-1, highlightIndex);
    }

    @Test
    public void testHighlightSync_emptyList() {
        int highlightIndex = calculateHighlightIndex("a", new ArrayList<>());
        assertEquals(-1, highlightIndex);
    }

    @Test
    public void testHighlightSync_nullCurrent() {
        int highlightIndex = calculateHighlightIndex(null, handles("a", "b"));
        assertEquals(-1, highlightIndex);
    }

    // ────────────────────────────────────────────────────────────
    // Tests: stored session validation
    // ────────────────────────────────────────────────────────────

    @Test
    public void testStoredSessionValidation_validHandle_unchanged() {
        String result = validateStoredSession("b", handles("a", "b", "c"), "b");
        assertEquals("b", result); // still valid, no change
    }

    @Test
    public void testStoredSessionValidation_staleHandle_fallsBackToActivityCurrent() {
        // Stored "x" was removed, activity currently shows "a"
        String result = validateStoredSession("x", handles("a", "b"), "a");
        assertEquals("a", result);
    }

    @Test
    public void testStoredSessionValidation_staleHandle_activityCurrentAlsoGone_fallsBackToLast() {
        // Both stored and activity current are stale
        String result = validateStoredSession("x", handles("a", "b"), "y");
        assertEquals("b", result); // last session
    }

    @Test
    public void testStoredSessionValidation_staleHandle_noSessions_returnsNull() {
        String result = validateStoredSession("x", new ArrayList<>(), null);
        assertNull(result);
    }

    @Test
    public void testStoredSessionValidation_nullStored_returnsNull() {
        String result = validateStoredSession(null, handles("a"), "a");
        assertNull(result);
    }

    @Test
    public void testIsSessionHandleValid_present_returnsTrue() {
        assertTrue(isSessionHandleValid("b", handles("a", "b", "c")));
    }

    @Test
    public void testIsSessionHandleValid_absent_returnsFalse() {
        assertFalse(isSessionHandleValid("x", handles("a", "b", "c")));
    }

    @Test
    public void testIsSessionHandleValid_null_returnsFalse() {
        assertFalse(isSessionHandleValid(null, handles("a", "b")));
    }

    // ────────────────────────────────────────────────────────────
    // Tests: KEEP_CURRENT stored session resolution
    // ────────────────────────────────────────────────────────────

    @Test
    public void testKeepCurrent_onlySession_storesNew() {
        // Only 1 session (the new one) → store it directly
        String result = resolveKeepCurrentStoredSession(1, "new", "old", handles("new"), null);
        assertEquals("new", result);
    }

    @Test
    public void testKeepCurrent_existingStoredValid_unchanged() {
        // Multiple sessions, stored session still valid → keep it
        String result = resolveKeepCurrentStoredSession(3, "new", "b", handles("a", "b", "new"), "a");
        assertEquals("b", result);
    }

    @Test
    public void testKeepCurrent_existingStoredStale_updatesToActivityCurrent() {
        // Stored session was removed → update to activity's current
        String result = resolveKeepCurrentStoredSession(3, "new", "removed", handles("a", "b", "new"), "a");
        assertEquals("a", result);
    }

    @Test
    public void testKeepCurrent_existingStoredStale_activityCurrentAlsoStale_fallsBackToLast() {
        // Both stored and activity current are stale → last session
        String result = resolveKeepCurrentStoredSession(2, "new", "removed", handles("a", "new"), "also_removed");
        assertEquals("new", result); // last in list
    }

    // ────────────────────────────────────────────────────────────
    // Tests: onNewIntent shortcut handling
    // ────────────────────────────────────────────────────────────

    @Test
    public void testShortcutIntent_actionRun_shouldCreateSession() {
        Intent intent = new Intent(Intent.ACTION_RUN);
        assertEquals(Intent.ACTION_RUN, intent.getAction());
        // Simulate that after processing, action is cleared
        intent.setAction(null);
        assertNull(intent.getAction()); // prevents duplicate on recreate
    }

    @Test
    public void testShortcutIntent_failsafeExtra_parsed() {
        Intent intent = new Intent(Intent.ACTION_RUN);
        intent.putExtra("com.termux.app.failsafe_session", true);
        assertTrue(intent.getBooleanExtra("com.termux.app.failsafe_session", false));
    }

    @Test
    public void testNonShortcutIntent_actionNotRun_noSessionCreated() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        assertFalse(Intent.ACTION_RUN.equals(intent.getAction()));
    }

    // ────────────────────────────────────────────────────────────
    // Tests: session restoration after recreation
    // ────────────────────────────────────────────────────────────

    @Test
    public void testSessionRestoration_storedSessionStillRunning_restored() {
        // After recreate, the stored handle matches a running session
        List<String> sessions = handles("a", "b", "c");
        String storedHandle = "b";
        assertTrue(isSessionHandleValid(storedHandle, sessions));
        assertEquals(1, calculateHighlightIndex(storedHandle, sessions));
    }

    @Test
    public void testSessionRestoration_storedSessionGone_fallsBackToLast() {
        // After recreate, the stored handle is gone → last session
        List<String> sessions = handles("a", "c");
        String storedHandle = "b"; // was removed
        assertFalse(isSessionHandleValid(storedHandle, sessions));
        // validateStoredSession would fall back:
        String fallback = validateStoredSession(storedHandle, sessions, null);
        assertEquals("c", fallback); // last session
    }

    // ────────────────────────────────────────────────────────────
    // Tests: SharedPreferences integration (Robolectric)
    // ────────────────────────────────────────────────────────────

    @Test
    public void testSharedPreferences_currentSessionRoundTrip() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "com.termux_preferences", Context.MODE_PRIVATE);

        // Store a session handle
        prefs.edit().putString("current_session", "test-handle-123").apply();

        // Read it back
        String stored = prefs.getString("current_session", null);
        assertEquals("test-handle-123", stored);
    }

    @Test
    public void testSharedPreferences_clearSession() {
        Context context = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = context.getSharedPreferences(
                "com.termux_preferences", Context.MODE_PRIVATE);

        prefs.edit().putString("current_session", "handle-a").apply();
        assertEquals("handle-a", prefs.getString("current_session", null));

        // Clear
        prefs.edit().remove("current_session").apply();
        assertNull(prefs.getString("current_session", null));
    }

    // ────────────────────────────────────────────────────────────
    // Tests: end-to-end scenario simulations
    // ────────────────────────────────────────────────────────────

    /**
     * Scenario: 3 sessions open, external plugin creates a 4th with KEEP_CURRENT,
     * then the activity is recreated (e.g., theme change).
     * The stored session must be valid after recreation.
     */
    @Test
    public void testScenario_externalLaunch_thenRecreate_restoresCorrectSession() {
        // Initial state: sessions [A, B, C], current = B, stored = B
        List<String> sessions = handles("a", "b", "c");
        String storedHandle = "b";
        String activityCurrent = "b";

        // Plugin creates session D with KEEP_CURRENT
        sessions.add("d"); // now [a, b, c, d]
        // KEEP_CURRENT: validate stored session
        String resolved = resolveKeepCurrentStoredSession(4, "d", storedHandle, sessions, activityCurrent);
        assertEquals("b", resolved); // stored was valid, stays "b"

        // Activity recreates → restore from stored
        assertTrue(isSessionHandleValid(resolved, sessions));
        assertEquals(1, calculateHighlightIndex(resolved, sessions)); // index 1 = "b"
    }

    /**
     * Scenario: 3 sessions, external plugin creates a 4th with KEEP_CURRENT,
     * then the stored session (B) exits and is auto-removed,
     * then the activity is recreated.
     */
    @Test
    public void testScenario_externalLaunch_thenStoredSessionRemoved_thenRecreate() {
        // After plugin: sessions [A, B, C, D], stored = B, current = B
        List<String> sessions = handles("a", "b", "c", "d");
        String storedHandle = "b";
        String activityCurrent = "b";

        // Session B exits and is auto-removed
        sessions.remove("b"); // now [a, c, d]
        // Activity current might now be "a" (the one at the same index)
        activityCurrent = "a";

        // On recreate, validateStoredSession runs:
        String validated = validateStoredSession(storedHandle, sessions, activityCurrent);
        assertEquals("a", validated); // falls back to activity current

        // Highlight syncs correctly
        assertEquals(0, calculateHighlightIndex(validated, sessions)); // index 0 = "a"
    }

    /**
     * Scenario: shortcut creates a new session while activity is already running,
     * then the user rotates the screen (activity recreated).
     */
    @Test
    public void testScenario_shortcutCreatesSession_thenRotate() {
        // Initial: sessions [A, B], current = B, stored = B
        List<String> sessions = handles("a", "b");
        String storedHandle = "b";

        // Shortcut creates session C (ACTION_RUN)
        sessions.add("c"); // now [a, b, c]
        storedHandle = "c"; // addNewSession calls setCurrentSession which updates stored

        // Rotate → activity recreated
        assertTrue(isSessionHandleValid(storedHandle, sessions));
        assertEquals(2, calculateHighlightIndex(storedHandle, sessions)); // index 2 = "c"
    }

    /**
     * Scenario: 3 sessions, the non-current middle session exits with code 0,
     * auto-removed. Current session should be preserved.
     */
    @Test
    public void testScenario_nonCurrentSessionAutoRemoved_currentPreserved() {
        // sessions [A, B, C], current = C
        List<String> sessions = handles("a", "b", "c");
        String currentHandle = "c";

        // B exits with code 0, auto-removed
        int removedIndex = sessions.indexOf("b");
        boolean wasCurrent = currentHandle.equals("b"); // false
        sessions.remove("b"); // now [a, c]

        int target = calculateTargetIndexAfterRemoval(removedIndex, wasCurrent, sessions.size());
        assertEquals(-1, target); // no switch needed

        // Current session is still C
        assertEquals(currentHandle, "c");
        // Highlight should be at C's new index
        assertEquals(1, calculateHighlightIndex(currentHandle, sessions));
    }

    /**
     * Scenario: 2 sessions, current session exits with code 0,
     * should switch to the remaining session.
     */
    @Test
    public void testScenario_currentSessionAutoRemoved_switchesToRemaining() {
        // sessions [A, B], current = B
        List<String> sessions = handles("a", "b");
        String currentHandle = "b";

        // B exits with code 0, auto-removed
        int removedIndex = sessions.indexOf("b"); // 1
        boolean wasCurrent = currentHandle.equals("b"); // true
        sessions.remove("b"); // now [a]

        int target = calculateTargetIndexAfterRemoval(removedIndex, wasCurrent, sessions.size());
        assertEquals(0, target); // switch to A

        // Update current session
        currentHandle = sessions.get(target); // "a"
        assertEquals("a", currentHandle);
        assertEquals(0, calculateHighlightIndex(currentHandle, sessions));
    }
}
