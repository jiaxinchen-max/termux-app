package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileManagerGridColumnsTest {

    private static final int PADDING_DP = 32;

    @Test
    public void portraitUsesFourColumns() {
        assertEquals(4, FileManagerGridColumns.columnsFor(320 - PADDING_DP, false));
        assertEquals(4, FileManagerGridColumns.columnsFor(360 - PADDING_DP, false));
        assertEquals(4, FileManagerGridColumns.columnsFor(411 - PADDING_DP, false));
        assertEquals(4, FileManagerGridColumns.columnsFor(600 - PADDING_DP, false));
        assertEquals(4, FileManagerGridColumns.columnsFor(1280, false));
    }

    @Test
    public void landscapeGrowsWithWidthUpToTen() {
        assertEquals(6, FileManagerGridColumns.columnsFor(480 - PADDING_DP, true));
        assertEquals(8, FileManagerGridColumns.columnsFor(640 - PADDING_DP, true));
        assertEquals(10, FileManagerGridColumns.columnsFor(800 - PADDING_DP, true));
        assertEquals(10, FileManagerGridColumns.columnsFor(1280 - PADDING_DP, true));
        assertEquals(10, FileManagerGridColumns.columnsFor(4000, true));
    }

    @Test
    public void cellsNeverShrinkBelowMinimum() {
        for (int width = 168; width <= 1600; width++) {
            for (boolean landscape : new boolean[] {false, true}) {
                int columns = FileManagerGridColumns.columnsFor(width, landscape);
                assertTrue(columns >= 2 && columns <= 10);
                assertTrue(width / columns >= FileManagerGridColumns.MIN_CELL_DP);
            }
        }
    }

    @Test
    public void listModeIsOneColumnExceptOnWideLandscape() {
        assertEquals(1, FileManagerGridColumns.listColumnsFor(360 - PADDING_DP, false));
        assertEquals(1, FileManagerGridColumns.listColumnsFor(1280, false));
        assertEquals(1, FileManagerGridColumns.listColumnsFor(559, true));
        assertEquals(2, FileManagerGridColumns.listColumnsFor(560, true));
    }

    @Test
    public void narrowWindowStillGetsOneColumn() {
        assertEquals(1, FileManagerGridColumns.columnsFor(100, false));
        assertEquals(1, FileManagerGridColumns.columnsFor(0, true));
    }

    @Test
    public void tilesFillWithoutOverflow() {
        int tile = FileManagerGridColumns.tileWidthPx(1000, 3, 10);
        assertEquals(313, tile);
        assertTrue(3 * (tile + 20) <= 1000);
        assertEquals(1, FileManagerGridColumns.tileWidthPx(10, 3, 10));
        assertEquals(960, FileManagerGridColumns.tileWidthPx(1000, 0, 20));
    }
}
