package com.termux.x11.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TaskManagerDialogSizingTest {
    @Test
    public void portraitPhoneStacksInfoPanelAndFillsWidth() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(411f, 914f);
        assertTrue(r.stackInfoPanel);
        assertEquals(379, r.widthDp);
        assertEquals(640, r.heightDp); // capped by MAX_HEIGHT_DP, 85% would be 776
        assertEquals(52, r.pidColumnDp);
        assertEquals(84, r.memoryColumnDp);
    }

    @Test
    public void landscapePhoneKeepsSideBySideLayout() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(891f, 411f);
        assertFalse(r.stackInfoPanel);
        assertEquals(720, r.widthDp);
        assertEquals(349, r.heightDp);
        assertEquals(60, r.pidColumnDp);
        assertEquals(110, r.memoryColumnDp);
    }

    @Test
    public void largeScreenIsCapped() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(1280f, 800f);
        assertFalse(r.stackInfoPanel);
        assertEquals(720, r.widthDp);
        assertEquals(640, r.heightDp);
    }

    @Test
    public void tabletPortraitBelowBreakpointStacksWithCompactCap() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(620f, 900f);
        assertTrue(r.stackInfoPanel);
        assertEquals(560, r.widthDp);
    }

    @Test
    public void wideThresholdIsDialogWidth() {
        assertTrue(TaskManagerDialogSizing.compute(631f, 800f).stackInfoPanel);
        assertFalse(TaskManagerDialogSizing.compute(632f, 800f).stackInfoPanel);
    }

    @Test
    public void narrowWindowShrinksColumnsAndNeverExceedsWindow() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(280f, 500f);
        assertTrue(r.stackInfoPanel);
        assertEquals(248, r.widthDp);
        assertTrue(r.widthDp <= 280 - 2 * TaskManagerDialogSizing.EDGE_MARGIN_DP);
        assertEquals(36, r.pidColumnDp);
        assertEquals(56, r.memoryColumnDp);
    }

    @Test
    public void shortWindowKeepsHeightWithinWindow() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(800f, 300f);
        assertEquals(255, r.heightDp);
        TaskManagerDialogSizing.Result tiny = TaskManagerDialogSizing.compute(800f, 150f);
        assertEquals(150, tiny.heightDp);
    }

    @Test
    public void degenerateInputDoesNotThrowOrGoNegative() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(0f, -5f);
        assertTrue(r.widthDp >= 0);
        assertTrue(r.heightDp >= 0);
        assertTrue(r.stackInfoPanel);
    }

    @Test
    public void subDialogWidthIsClamped() {
        assertEquals(347, TaskManagerDialogSizing.subDialogContentWidthDp(411f));
        assertEquals(360, TaskManagerDialogSizing.subDialogContentWidthDp(891f));
        assertEquals(200, TaskManagerDialogSizing.subDialogContentWidthDp(240f));
    }

    @Test
    public void portraitPhoneShowsFullDashboardInTwoColumnGridWithCardsSideBySide() {
        for (int cores : new int[]{4, 8}) {
            TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(411f, 914f, cores);
            assertTrue(r.fullDashboard);
            assertTrue(r.stackInfoPanel);
            assertEquals(2, r.cpuGridColumns);
            assertTrue(r.cardsSideBySide);
            int rows = cores / 2;
            assertEquals(16 + 22 + rows * 20, r.infoPanelHeightDp);
        }
    }

    @Test
    public void portraitPhoneKeepsAtLeastFiveProcessRows() {
        for (int cores : new int[]{1, 4, 8, 16, 64}) {
            TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(411f, 914f, cores);
            int table = r.heightDp - TaskManagerDialogSizing.DIALOG_CHROME_DP - r.infoPanelHeightDp - 8;
            assertTrue("cores=" + cores, table >= TaskManagerDialogSizing.MIN_TABLE_PANEL_DP);
        }
    }

    @Test
    public void manyCoresCapDashboardHeight() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(411f, 914f, 64);
        assertEquals(640 - 120 - TaskManagerDialogSizing.MIN_TABLE_PANEL_DP - 8, r.infoPanelHeightDp);
    }

    @Test
    public void landscapePhoneIsSideBySideWithSingleColumnAndFullDashboard() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(891f, 411f, 8);
        assertFalse(r.stackInfoPanel);
        assertTrue(r.fullDashboard);
        assertEquals(1, r.cpuGridColumns);
        assertFalse(r.cardsSideBySide);
        assertEquals(0, r.infoPanelHeightDp);
    }

    @Test
    public void largeScreenIsSideBySideFullDashboard() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(1280f, 800f, 4);
        assertFalse(r.stackInfoPanel);
        assertTrue(r.fullDashboard);
        assertEquals(1, r.cpuGridColumns);
    }

    @Test
    public void extremelyNarrowWindowStacksCardsWithSingleColumnGrid() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(280f, 500f, 8);
        assertTrue(r.stackInfoPanel);
        assertTrue(r.fullDashboard);
        assertFalse(r.cardsSideBySide);
        assertEquals(1, r.cpuGridColumns);
        assertEquals(TaskManagerDialogSizing.MIN_INFO_PANEL_DP, r.infoPanelHeightDp); // capped, dashboard scrolls
    }

    @Test
    public void midWidthStacksCardsButKeepsTwoColumnGrid() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.compute(360f, 800f, 4);
        assertTrue(r.stackInfoPanel);
        assertEquals(2, r.cpuGridColumns);
        assertFalse(r.cardsSideBySide);
    }

    @Test
    public void singleCoreUsesOneColumn() {
        assertEquals(1, TaskManagerDialogSizing.compute(411f, 914f, 1).cpuGridColumns);
        assertEquals(1, TaskManagerDialogSizing.compute(411f, 914f, 0).cpuGridColumns);
    }

    @Test
    public void embeddedIsAlwaysStackedWithFullDashboardAndNoDialogMargins() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.computeEmbedded(380f, 520f, 8);
        assertTrue(r.stackInfoPanel);
        assertTrue(r.fullDashboard);
        assertEquals(380, r.widthDp);
        assertEquals(520, r.heightDp);
        assertEquals(2, r.cpuGridColumns);
        assertTrue(r.cardsSideBySide);
        assertEquals(52, r.pidColumnDp);
        // at least five process rows remain below the dashboard
        assertTrue(r.heightDp - r.infoPanelHeightDp - TaskManagerDialogSizing.CARD_GAP_DP
            >= TaskManagerDialogSizing.MIN_TABLE_PANEL_DP);
    }

    @Test
    public void embeddedNarrowSideColumnStacksCardsAndCapsDashboardHeight() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.computeEmbedded(280f, 300f, 8);
        assertTrue(r.stackInfoPanel);
        assertFalse(r.cardsSideBySide);
        assertEquals(1, r.cpuGridColumns);
        assertEquals(TaskManagerDialogSizing.MIN_INFO_PANEL_DP, r.infoPanelHeightDp);
    }

    @Test
    public void embeddedTallContainerShowsWholeDashboard() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.computeEmbedded(300f, 900f, 4);
        assertEquals(2, r.cpuGridColumns);
        assertFalse(r.cardsSideBySide);
        int cpuCard = TaskManagerDialogSizing.CARD_PADDING_DP + TaskManagerDialogSizing.CARD_TITLE_DP + 2 * TaskManagerDialogSizing.CPU_LINE_DP;
        int memCard = TaskManagerDialogSizing.CARD_PADDING_DP + TaskManagerDialogSizing.CARD_TITLE_DP + TaskManagerDialogSizing.CPU_LINE_DP;
        assertEquals(cpuCard + TaskManagerDialogSizing.CARD_GAP_DP + memCard, r.infoPanelHeightDp);
    }

    @Test
    public void embeddedNegativeOrZeroSizesAreClampedAndSingleCoreUsesOneColumn() {
        TaskManagerDialogSizing.Result r = TaskManagerDialogSizing.computeEmbedded(-5f, 0f, 0);
        assertEquals(0, r.widthDp);
        assertEquals(0, r.heightDp);
        assertEquals(1, r.cpuGridColumns);
        assertEquals(1, TaskManagerDialogSizing.computeEmbedded(400f, 600f, 1).cpuGridColumns);
    }
}
