package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.x11.session.TaskManagerDialogSizing;

import org.junit.Test;

public class DrawerProcessPageSizingTest {

    private static DrawerProcessPageSizing.Result compute(int w, int h, int cores) {
        return DrawerProcessPageSizing.compute(w, h,
            OverlayDrawerSizing.placementFor(w, h), cores);
    }

    @Test
    public void portraitPhoneIsStackedBottomSheetWithinEightyFivePercent() {
        DrawerProcessPageSizing.Result r = compute(411, 914, 8);
        assertTrue(r.explicitHeight);
        assertTrue(r.footerFixed);
        assertTrue(r.layout.stackInfoPanel);
        assertTrue(r.layout.fullDashboard);
        assertEquals(411 - 2 * DrawerProcessPageSizing.DRAWER_PADDING_DP, r.panelWidthDp);
        int sheet = r.panelHeightDp + 2 * DrawerProcessPageSizing.DRAWER_PADDING_DP
            + DrawerProcessPageSizing.HEADER_DP + DrawerProcessPageSizing.FOOTER_DP
            + DrawerProcessPageSizing.SHEET_NAV_STRIP_DP;
        assertTrue(sheet <= Math.round(914 * 0.85f));
    }

    @Test
    public void portraitSheetReservesTabStripHeaderAndFooterUnderTheCap() {
        int[][] windows = {{360, 640}, {411, 914}, {393, 852}, {600, 1024}};
        for (int[] w : windows) {
            DrawerProcessPageSizing.Result r = compute(w[0], w[1], 8);
            int cap = Math.round(
                (w[1] - DrawerProcessPageSizing.SHEET_SYSTEM_INSETS_DP) * OverlayDrawerSizing.BOTTOM_SHEET_MAX_HEIGHT_FRACTION);
            // everything stacked in the sheet: padding, tab strip, header, page = panel + footer
            int stacked = 2 * DrawerProcessPageSizing.DRAWER_PADDING_DP
                + DrawerProcessPageSizing.SHEET_NAV_STRIP_DP + DrawerProcessPageSizing.HEADER_DP
                + r.panelHeightDp + DrawerProcessPageSizing.FOOTER_DP;
            assertTrue("window " + w[0] + "x" + w[1], stacked <= cap);
            assertTrue(r.panelHeightDp > DrawerProcessPageSizing.MIN_PANEL_HEIGHT_DP);
        }
    }

    @Test
    public void sideDrawerDoesNotReserveTheTabStrip() {
        DrawerProcessPageSizing.Result r = compute(891, 411, 8);
        int expected = Math.max(DrawerProcessPageSizing.MIN_PANEL_HEIGHT_DP, 411
            - DrawerProcessPageSizing.SYSTEM_INSETS_DP - 2 * DrawerProcessPageSizing.DRAWER_PADDING_DP
            - DrawerProcessPageSizing.HEADER_DP - DrawerProcessPageSizing.FOOTER_DP);
        assertEquals(expected, r.panelHeightDp);
    }

    @Test
    public void landscapePhoneIsNarrowSideColumnStillStacked() {
        DrawerProcessPageSizing.Result r = compute(891, 411, 8);
        assertFalse(r.explicitHeight);
        // short landscape window: the footer scrolls at the end of the page
        assertFalse(r.footerFixed);
        assertEquals(OverlayDrawerSizing.MAX_DRAWER_WIDTH_DP - 2 * DrawerProcessPageSizing.DRAWER_PADDING_DP,
            r.panelWidthDp);
        assertTrue(r.layout.stackInfoPanel);
        assertTrue(r.layout.fullDashboard);
        assertEquals(2, r.layout.cpuGridColumns);
        // short window: dashboard is capped (scrolls) and the list keeps the remaining height
        assertTrue(r.layout.infoPanelHeightDp >= TaskManagerDialogSizing.MIN_INFO_PANEL_DP);
        assertTrue(r.layout.infoPanelHeightDp < r.panelHeightDp);
    }

    @Test
    public void narrowWindowSideColumnIsWindowWidthMinusMargin() {
        DrawerProcessPageSizing.Result r = DrawerProcessPageSizing.compute(
            300, 300, OverlayDrawerSizing.Placement.SIDE, 4);
        assertEquals(300 - OverlayDrawerSizing.WINDOW_MARGIN_DP - 2 * DrawerProcessPageSizing.DRAWER_PADDING_DP,
            r.panelWidthDp);
        assertTrue(r.layout.stackInfoPanel);
    }

    @Test
    public void largeTabletSideDrawerKeepsFullDashboardInStackedLayout() {
        DrawerProcessPageSizing.Result r = compute(1280, 800, 4);
        assertFalse(r.explicitHeight);
        assertTrue(r.layout.stackInfoPanel);
        assertTrue(r.layout.cardsSideBySide);
        assertEquals(2, r.layout.cpuGridColumns);
    }

    @Test
    public void tallWindowsKeepFooterFixedAndShortOnesScrollIt() {
        assertTrue(compute(1280, 800, 8).footerFixed);
        assertTrue(compute(1280, DrawerProcessPageSizing.FIXED_FOOTER_MIN_WINDOW_HEIGHT_DP, 8).footerFixed);
        assertFalse(compute(1280, DrawerProcessPageSizing.FIXED_FOOTER_MIN_WINDOW_HEIGHT_DP - 1, 8).footerFixed);
    }

    @Test
    public void portraitBottomSheetKeepsFooterFixedOnTypicalPhones() {
        int[][] windows = {{360, 640}, {411, 914}, {393, 852}, {600, 1024}};
        for (int[] w : windows) {
            DrawerProcessPageSizing.Result r = compute(w[0], w[1], 8);
            assertEquals(OverlayDrawerSizing.Placement.BOTTOM_SHEET,
                OverlayDrawerSizing.placementFor(w[0], w[1]));
            assertTrue(r.explicitHeight);
            assertTrue(r.footerFixed);
        }
    }

    @Test
    public void extremelyShortBottomSheetScrollsFooterWithContent() {
        DrawerProcessPageSizing.Result r = DrawerProcessPageSizing.compute(
            300, 300, OverlayDrawerSizing.Placement.BOTTOM_SHEET, 8);
        assertTrue(r.explicitHeight);
        assertFalse(r.footerFixed);
        assertEquals(DrawerProcessPageSizing.MIN_PANEL_HEIGHT_DP, r.panelHeightDp);
    }

    @Test
    public void sideDrawersNeverNeedAnExplicitHeight() {
        int[][] windows = {{891, 411}, {1280, 800}, {640, 360}, {2000, 1200}};
        for (int[] w : windows) {
            assertEquals(OverlayDrawerSizing.Placement.SIDE, OverlayDrawerSizing.placementFor(w[0], w[1]));
            assertFalse(compute(w[0], w[1], 8).explicitHeight);
        }
    }

    @Test
    public void tinyWindowStillGetsMinimumPanelHeightAndNeverNegative() {
        DrawerProcessPageSizing.Result r = compute(0, 0, 0);
        assertEquals(0, r.panelWidthDp);
        assertEquals(DrawerProcessPageSizing.MIN_PANEL_HEIGHT_DP, r.panelHeightDp);
        assertTrue(r.layout.stackInfoPanel);
    }

    @Test
    public void footerFixedDependsOnlyOnWindowHeightForBothDrawerForms() {
        int threshold = DrawerProcessPageSizing.FIXED_FOOTER_MIN_WINDOW_HEIGHT_DP;
        for (OverlayDrawerSizing.Placement placement : OverlayDrawerSizing.Placement.values()) {
            for (int width : new int[] {360, 891, 1280}) {
                assertTrue(DrawerProcessPageSizing.compute(width, threshold, placement, 8).footerFixed);
                assertFalse(DrawerProcessPageSizing.compute(width, threshold - 1, placement, 8).footerFixed);
            }
        }
    }

    @Test
    public void performancePageAlwaysUsesTheStackedWholePageLayout() {
        int[][] windows = {{360, 640}, {411, 914}, {891, 411}, {1280, 800}, {300, 300}};
        for (int[] w : windows) {
            DrawerProcessPageSizing.Result r = compute(w[0], w[1], 8);
            assertTrue(r.layout.stackInfoPanel);
            assertTrue(r.panelHeightDp >= DrawerProcessPageSizing.MIN_PANEL_HEIGHT_DP);
        }
    }
}
