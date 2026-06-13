package com.termux.app.terminal.io;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.Assert;
import org.junit.Test;

/**
 * Tests for {@link ExtraKeysInfo} parsing — verifies that the JSON configuration
 * is correctly translated into a button matrix and that different styles produce
 * the expected display maps.
 *
 * These tests exercise the same parsing logic that {@link TermuxTerminalExtraKeys#reload()}
 * relies on after a properties reload, so they serve as regression coverage for
 * the setting-reload chain.
 */
public class TermuxTerminalExtraKeysTest {

    // ---- Matrix shape tests ------------------------------------------------

    @Test
    public void testDefaultExtraKeysProducesTwoRows() throws Exception {
        ExtraKeysInfo info = new ExtraKeysInfo(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE,
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        Assert.assertNotNull("ExtraKeysInfo should not be null", info);
        Assert.assertEquals("Default config should produce 2 rows", 2, info.getMatrix().length);
        Assert.assertEquals("Row 0 should have 7 columns", 7, info.getMatrix()[0].length);
        Assert.assertEquals("Row 1 should have 7 columns", 7, info.getMatrix()[1].length);
    }

    @Test
    public void testSingleRowConfig() throws Exception {
        String json = "[[ESC, TAB, CTRL]]";
        ExtraKeysInfo info = new ExtraKeysInfo(json, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        Assert.assertEquals(1, info.getMatrix().length);
        Assert.assertEquals(3, info.getMatrix()[0].length);
    }

    @Test
    public void testThreeRowConfig() throws Exception {
        String json = "[[ESC, TAB], [CTRL, ALT], [LEFT, RIGHT]]";
        ExtraKeysInfo info = new ExtraKeysInfo(json, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        Assert.assertEquals(3, info.getMatrix().length);
    }

    @Test
    public void testEmptyMatrix() throws Exception {
        String json = "[]";
        ExtraKeysInfo info = new ExtraKeysInfo(json, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        Assert.assertEquals(0, info.getMatrix().length);
    }

    // ---- Style tests -------------------------------------------------------

    @Test
    public void testArrowsOnlyStyle() throws Exception {
        String json = "[[LEFT, DOWN, UP, RIGHT]]";
        ExtraKeysInfo info = new ExtraKeysInfo(json, "arrows-only", ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        Assert.assertEquals(1, info.getMatrix().length);
        Assert.assertEquals(4, info.getMatrix()[0].length);
        // arrows-only style maps LEFT → "←", DOWN → "↓", etc.
        Assert.assertEquals("←", info.getMatrix()[0][0].getDisplay());
        Assert.assertEquals("↓", info.getMatrix()[0][1].getDisplay());
    }

    @Test
    public void testNoneStyleUsesRawKeyName() throws Exception {
        String json = "[[ESC, TAB]]";
        ExtraKeysInfo info = new ExtraKeysInfo(json, "none", ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        // "none" style uses an empty display map, so display falls back to the
        // raw key name itself (or the custom display field if present).
        Assert.assertEquals("ESC", info.getMatrix()[0][0].getDisplay());
        Assert.assertEquals("TAB", info.getMatrix()[0][1].getDisplay());
    }

    @Test
    public void testCustomDisplayField() throws Exception {
        String json = "[[{key: ESC, display: 'Escape'}]]";
        ExtraKeysInfo info = new ExtraKeysInfo(json, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        Assert.assertEquals("Escape", info.getMatrix()[0][0].getDisplay());
    }

    // ---- Reload scenario regression tests ----------------------------------

    @Test
    public void testRowCountChangesOnReload() throws Exception {
        // Simulate the scenario where the user changes extra-keys from 2 rows
        // to 1 row in termux.properties and runs termux-reload-settings.

        // Before reload: default 2-row config
        ExtraKeysInfo before = new ExtraKeysInfo(
            TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        Assert.assertEquals(2, before.getMatrix().length);

        // After reload: user changed to single-row config
        ExtraKeysInfo after = new ExtraKeysInfo(
            "[[ESC, TAB, CTRL, ALT, DOWN, UP]]", "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        Assert.assertEquals(1, after.getMatrix().length);

        // The toolbar height calculation should use the new row count
        int oldHeight = Math.round(100 * before.getMatrix().length * 1.0f);
        int newHeight = Math.round(100 * after.getMatrix().length * 1.0f);
        Assert.assertEquals(200, oldHeight);
        Assert.assertEquals(100, newHeight);
    }

    @Test
    public void testAliasResolution() throws Exception {
        // CONTROL_CHARS_ALIASES maps "ESCAPE" → "ESC", "RETURN" → "ENTER", etc.
        String json = "[[ESCAPE, RETURN]]";
        ExtraKeysInfo info = new ExtraKeysInfo(json, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        // The key name should be resolved via the alias map
        Assert.assertEquals("ESC", info.getMatrix()[0][0].getKey());
        Assert.assertEquals("ENTER", info.getMatrix()[0][1].getKey());
    }

    @Test
    public void testPopupKeyParsing() throws Exception {
        String json = "[[{key: '-', popup: '|'}]]";
        ExtraKeysInfo info = new ExtraKeysInfo(json, "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);

        Assert.assertEquals(1, info.getMatrix().length);
        Assert.assertNotNull("Popup button should be parsed", info.getMatrix()[0][0].getPopup());
        Assert.assertEquals("|", info.getMatrix()[0][0].getPopup().getKey());
    }
}
