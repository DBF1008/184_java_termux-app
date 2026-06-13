package com.termux.app;

import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for the toolbar height calculation extracted into
 * {@link TermuxActivity#calculateToolbarHeight(int, int, float)}.
 *
 * These cover the scenarios most likely to break after a settings reload:
 * row-count changes, scale-factor extremes, and rounding edge cases.
 */
public class TermuxActivityToolbarTest {

    @Test
    public void testDefaultTwoRows() {
        // Default: 100px per row, 2 rows, scale 1.0 → 200px
        Assert.assertEquals(200, TermuxActivity.calculateToolbarHeight(100, 2, 1.0f));
    }

    @Test
    public void testSingleRow() {
        Assert.assertEquals(100, TermuxActivity.calculateToolbarHeight(100, 1, 1.0f));
    }

    @Test
    public void testThreeRows() {
        Assert.assertEquals(300, TermuxActivity.calculateToolbarHeight(100, 3, 1.0f));
    }

    @Test
    public void testZeroRows() {
        // Empty matrix (extra-keys = []) should produce 0 height
        Assert.assertEquals(0, TermuxActivity.calculateToolbarHeight(100, 0, 1.0f));
    }

    @Test
    public void testScaleFactor1_5() {
        // 100 × 2 × 1.5 = 300
        Assert.assertEquals(300, TermuxActivity.calculateToolbarHeight(100, 2, 1.5f));
    }

    @Test
    public void testMinimumScaleFactor() {
        // Minimum allowed scale is 0.4
        Assert.assertEquals(40, TermuxActivity.calculateToolbarHeight(100, 1, 0.4f));
    }

    @Test
    public void testMaximumScaleFactor() {
        // Maximum allowed scale is 3.0; 100 × 3 × 3.0 = 900
        Assert.assertEquals(900, TermuxActivity.calculateToolbarHeight(100, 3, 3.0f));
    }

    @Test
    public void testRoundingHalfUp() {
        // 37 × 2 × 1.3 = 96.2 → should round to 96
        Assert.assertEquals(96, TermuxActivity.calculateToolbarHeight(37, 2, 1.3f));
    }

    @Test
    public void testRoundingUpAtHalf() {
        // 33 × 1 × 1.5 = 49.5 → Math.round rounds 0.5 up → 50
        Assert.assertEquals(50, TermuxActivity.calculateToolbarHeight(33, 1, 1.5f));
    }

    @Test
    public void testActualDefaultLayoutHeight() {
        // Real-world: 37.5dp default height, 2 rows, scale 1.0
        // 37.5 × 2 × 1.0 = 75 (in dp-converted pixels the base value would differ,
        // but the formula itself is unit-agnostic)
        Assert.assertEquals(75, TermuxActivity.calculateToolbarHeight(37, 2, 1.0f));
    }

    @Test
    public void testScaleFactorAfterReload() {
        // Scenario: user changes terminal-toolbar-height from 1.0 to 2.0 and reloads
        int before = TermuxActivity.calculateToolbarHeight(100, 2, 1.0f);
        int after  = TermuxActivity.calculateToolbarHeight(100, 2, 2.0f);

        Assert.assertEquals(200, before);
        Assert.assertEquals(400, after);
        // Height should have doubled
        Assert.assertEquals(before * 2, after);
    }

    @Test
    public void testRowCountChangeAfterReload() {
        // Scenario: user changes extra-keys from 2 rows to 3 rows and reloads
        int before = TermuxActivity.calculateToolbarHeight(100, 2, 1.0f);
        int after  = TermuxActivity.calculateToolbarHeight(100, 3, 1.0f);

        Assert.assertEquals(200, before);
        Assert.assertEquals(300, after);
    }
}
