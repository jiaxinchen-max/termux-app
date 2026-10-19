package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DialogSizingTest {

    private static final float DENSITY = 2f;

    private static int px(int dp) {
        return Math.round(dp * DENSITY);
    }

    @Test
    public void orientationFollowsTheLongerWindowSide() {
        assertTrue(DialogSizing.isLandscape(px(640), px(360)));
        assertFalse(DialogSizing.isLandscape(px(360), px(640)));
        assertFalse(DialogSizing.isLandscape(px(500), px(500)));
    }

    @Test
    public void widthKeepsTheMarginOnNarrowWindows() {
        assertEquals(px(360 - 32), DialogSizing.maxWidthPx(px(360), DENSITY));
    }

    @Test
    public void widthIsCappedOnWideWindows() {
        assertEquals(px(DialogSizing.MAX_WIDTH_DP), DialogSizing.maxWidthPx(px(1280), DENSITY));
    }

    @Test
    public void heightKeepsTheMarginInShortLandscapeWindows() {
        assertEquals(px(360 - 32), DialogSizing.maxHeightPx(px(360), DENSITY));
    }

    @Test
    public void tinyWindowsNeverProduceNegativeSizes() {
        assertEquals(0, DialogSizing.maxWidthPx(px(10), DENSITY));
        assertEquals(0, DialogSizing.maxHeightPx(px(10), DENSITY));
        assertEquals(0, DialogSizing.clamp(px(100), -5));
    }

    @Test
    public void clampLimitsToTheMaximum() {
        assertEquals(100, DialogSizing.clamp(100, 200));
        assertEquals(200, DialogSizing.clamp(500, 200));
    }

    @Test
    public void fractionsStayInsideTheMargins() {
        assertEquals(px(360 - 32), DialogSizing.fractionOfWidthPx(px(360), 1f, DENSITY));
        assertEquals(Math.round(px(640) * 0.5f),
            DialogSizing.fractionOfHeightPx(px(640), 0.5f, DENSITY));
    }

    @Test
    public void listLeavesRoomForTheRestOfTheDialogInShortWindows() {
        // 360 dp high landscape window: 45 % = 162 dp, not the fixed 300 dp that overflowed.
        assertEquals(px(162), DialogSizing.listHeightPx(px(360), DENSITY));
    }

    @Test
    public void listIsCappedOnTallWindowsAndHasAFloor() {
        assertEquals(px(DialogSizing.MAX_LIST_HEIGHT_DP), DialogSizing.listHeightPx(px(900), DENSITY));
        assertEquals(px(DialogSizing.MIN_LIST_HEIGHT_DP), DialogSizing.listHeightPx(px(200), DENSITY));
    }

    @Test
    public void consoleGetsItsMinimumHeightWhenTheWindowAllows() {
        // 64 % of 360 dp would be 230 dp; the console wants at least 280 dp.
        assertEquals(px(DialogSizing.MIN_CONSOLE_HEIGHT_DP),
            DialogSizing.consoleHeightPx(px(360), DENSITY));
        assertEquals(Math.round(px(800) * 0.64f), DialogSizing.consoleHeightPx(px(800), DENSITY));
    }

    @Test
    public void consoleNeverExceedsTheWindowMargins() {
        assertEquals(px(300 - 32), DialogSizing.consoleHeightPx(px(300), DENSITY));
    }

    @Test
    public void popupLineUpsWithTheAnchorRightEdge() {
        assertEquals(300, DialogSizing.popupLeftPx(500, 200, 800, 16));
    }

    @Test
    public void popupStaysInsideTheWindowHorizontally() {
        assertEquals(16, DialogSizing.popupLeftPx(100, 200, 800, 16));
        assertEquals(800 - 200 - 16, DialogSizing.popupLeftPx(900, 200, 800, 16));
    }

    @Test
    public void popupPrefersAboveTheAnchor() {
        assertEquals(300 - 240, DialogSizing.popupTopPx(300, 348, 240, 720, 16));
    }

    @Test
    public void popupFallsBelowTheAnchorWhenThereIsNoRoomAbove() {
        assertEquals(148, DialogSizing.popupTopPx(100, 148, 240, 720, 16));
    }

    @Test
    public void popupIsPushedIntoTheWindowWhenNeitherSideFits() {
        // 360 dp high window, 328 dp popup, anchor in the middle: pinned inside the margins.
        assertEquals(16, DialogSizing.popupTopPx(150, 198, 328, 360, 16));
    }
}
