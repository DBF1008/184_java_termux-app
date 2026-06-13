package com.termux.app.terminal.io;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;

import org.json.JSONException;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * Regression tests for the terminal toolbar sizing logic that drives the "extra keys" toolbar.
 *
 * These guard the settings-effect chain that was broken when {@code extra-keys}/{@code extra-keys-style}
 * were changed and applied via an in-place reload (e.g. {@code termux-reload-settings}) or a style
 * switch: the toolbar height and key matrix must always be derived from the <i>current</i> extra keys
 * info, never a stale cached value. The height in
 * {@link com.termux.app.TermuxActivity#setTerminalToolbarHeight()} is computed purely from
 * {@link TerminalToolbarViewPager#getExtraKeysMatrixRowCount(ExtraKeysInfo)} and
 * {@link TerminalToolbarViewPager#calculateTerminalToolbarHeight(float, int, float)}, so exercising
 * those with changing matrices reproduces the reload/style-switch scenarios.
 *
 * Robolectric is required because {@link ExtraKeysInfo} parses its matrix with {@code org.json},
 * which is only a stub under plain JVM unit tests.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class TerminalToolbarViewPagerTest {

    private static ExtraKeysInfo extraKeys(String propertiesInfo) throws JSONException {
        return extraKeys(propertiesInfo, "default");
    }

    private static ExtraKeysInfo extraKeys(String propertiesInfo, String style) throws JSONException {
        return new ExtraKeysInfo(propertiesInfo, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
    }

    // ---- Row count ---------------------------------------------------------------------------

    @Test
    public void rowCount_isZero_whenInfoIsNull() {
        // A null info (extra keys failed to load) must collapse the toolbar, not crash.
        Assert.assertEquals(0, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(null));
    }

    @Test
    public void rowCount_isZero_forEmptyMatrix() throws JSONException {
        Assert.assertEquals(0, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(extraKeys("[]")));
    }

    @Test
    public void rowCount_matchesNumberOfMatrixRows() throws JSONException {
        Assert.assertEquals(1, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(
            extraKeys("[['ESC','TAB','CTRL','ALT']]")));
        Assert.assertEquals(2, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(
            extraKeys("[['ESC','/','HOME','UP','END'],['TAB','CTRL','ALT','LEFT','DOWN']]")));
        Assert.assertEquals(3, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(
            extraKeys("[['ESC'],['CTRL'],['ALT']]")));
    }

    // ---- Height computation ------------------------------------------------------------------

    @Test
    public void height_isZero_whenThereAreNoRows() {
        Assert.assertEquals(0, TerminalToolbarViewPager.calculateTerminalToolbarHeight(100f, 0, 1f));
    }

    @Test
    public void height_scalesLinearlyWithRowCount() {
        Assert.assertEquals(100, TerminalToolbarViewPager.calculateTerminalToolbarHeight(100f, 1, 1f));
        Assert.assertEquals(200, TerminalToolbarViewPager.calculateTerminalToolbarHeight(100f, 2, 1f));
        Assert.assertEquals(300, TerminalToolbarViewPager.calculateTerminalToolbarHeight(100f, 3, 1f));
    }

    @Test
    public void height_appliesScaleFactorAndRoundsHalfUp() {
        Assert.assertEquals(240, TerminalToolbarViewPager.calculateTerminalToolbarHeight(100f, 3, 0.8f));
        Assert.assertEquals(120, TerminalToolbarViewPager.calculateTerminalToolbarHeight(120f, 2, 0.5f));
        // 3 * 1 * 0.5 = 1.5 must round up to 2, not truncate to 1.
        Assert.assertEquals(2, TerminalToolbarViewPager.calculateTerminalToolbarHeight(3f, 1, 0.5f));
    }

    // ---- Settings reload scenario ------------------------------------------------------------

    @Test
    public void settingsReload_rowCountChange_updatesHeight() throws JSONException {
        float defaultHeightPerRow = 150f;
        float scaleFactor = 1f;

        // Before reload: a single row of extra keys.
        ExtraKeysInfo before = extraKeys("[['ESC','TAB','CTRL','ALT']]");
        int heightBefore = TerminalToolbarViewPager.calculateTerminalToolbarHeight(
            defaultHeightPerRow, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(before), scaleFactor);

        // After termux-reload-settings with a second row added by the user.
        ExtraKeysInfo after = extraKeys("[['ESC','TAB','CTRL','ALT'],['HOME','END','UP','DOWN']]");
        int heightAfter = TerminalToolbarViewPager.calculateTerminalToolbarHeight(
            defaultHeightPerRow, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(after), scaleFactor);

        // The height must follow the new matrix, not stay stuck at the old single-row height.
        Assert.assertEquals(150, heightBefore);
        Assert.assertEquals(300, heightAfter);
        Assert.assertNotEquals(heightBefore, heightAfter);
    }

    @Test
    public void settingsReload_toEmptyMatrix_collapsesToolbar() throws JSONException {
        ExtraKeysInfo before = extraKeys("[['ESC','TAB']]");
        Assert.assertEquals(150, TerminalToolbarViewPager.calculateTerminalToolbarHeight(
            150f, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(before), 1f));

        // User clears extra-keys to [] and reloads: toolbar should collapse to zero height.
        ExtraKeysInfo after = extraKeys("[]");
        Assert.assertEquals(0, TerminalToolbarViewPager.calculateTerminalToolbarHeight(
            150f, TerminalToolbarViewPager.getExtraKeysMatrixRowCount(after), 1f));
    }

    @Test
    public void settingsReload_heightScaleFactorChange_updatesHeight() throws JSONException {
        ExtraKeysInfo info = extraKeys("[['ESC','TAB'],['CTRL','ALT']]");
        int rows = TerminalToolbarViewPager.getExtraKeysMatrixRowCount(info);

        // Same two-row matrix, but the terminal-toolbar-height factor was changed and reloaded.
        Assert.assertEquals(200, TerminalToolbarViewPager.calculateTerminalToolbarHeight(100f, rows, 1f));
        Assert.assertEquals(100, TerminalToolbarViewPager.calculateTerminalToolbarHeight(100f, rows, 0.5f));
    }

    // ---- Style switch scenario ---------------------------------------------------------------

    @Test
    public void styleSwitch_refreshesMatrix_keepingConsistentHeight() throws JSONException {
        String sameExtraKeys = "[['ESC','/','HOME','UP','END'],['TAB','CTRL','ALT','LEFT','DOWN']]";

        // Switching the extra-keys-style must still yield a valid, equally-sized matrix so that the
        // toolbar reload does not collapse or mis-size the toolbar after a style switch.
        ExtraKeysInfo defaultStyle = extraKeys(sameExtraKeys, "default");
        ExtraKeysInfo arrowsOnly = extraKeys(sameExtraKeys, "arrows-only");

        int defaultRows = TerminalToolbarViewPager.getExtraKeysMatrixRowCount(defaultStyle);
        int arrowsRows = TerminalToolbarViewPager.getExtraKeysMatrixRowCount(arrowsOnly);

        Assert.assertEquals(2, defaultRows);
        Assert.assertEquals(defaultRows, arrowsRows);
        Assert.assertEquals(
            TerminalToolbarViewPager.calculateTerminalToolbarHeight(140f, defaultRows, 1f),
            TerminalToolbarViewPager.calculateTerminalToolbarHeight(140f, arrowsRows, 1f));
    }
}
