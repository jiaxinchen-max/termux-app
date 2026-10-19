package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LibraryGridColumnsTest {

    @Test
    public void portraitPhoneKeepsTwoColumns() {
        assertEquals(2, LibraryGridColumns.columnsFor(328, false));
        assertEquals(2, LibraryGridColumns.columnsFor(379, false));
    }

    @Test
    public void portraitNeverDropsBelowTwoColumns() {
        assertEquals(2, LibraryGridColumns.columnsFor(200, false));
        assertEquals(2, LibraryGridColumns.columnsFor(0, false));
    }

    @Test
    public void portraitTabletAndWideWindowsGetMoreColumns() {
        assertEquals(3, LibraryGridColumns.columnsFor(568, false));
        assertEquals(4, LibraryGridColumns.columnsFor(704, false));
        assertEquals(6, LibraryGridColumns.columnsFor(1600, false));
    }

    @Test
    public void landscapeUsesTheAvailableWidth() {
        // 720 dp window - 2 x 16 dp page padding = 688 dp (the top tab bar takes height, not width).
        assertEquals(2, LibraryGridColumns.columnsFor(688, true));
        assertEquals(3, LibraryGridColumns.columnsFor(900, true));
    }

    @Test
    public void landscapeFallsBackToOneColumnInANarrowWindowAndIsCapped() {
        assertEquals(1, LibraryGridColumns.columnsFor(200, true));
        assertEquals(1, LibraryGridColumns.columnsFor(0, true));
        assertEquals(LibraryGridColumns.LANDSCAPE_MAX_COLUMNS,
            LibraryGridColumns.columnsFor(4000, true));
    }

    @Test
    public void cardWidthIsTheColumnShareMinusTheGutter() {
        assertEquals(500 / 2 - 16, LibraryGridColumns.cardWidthPx(500, 2, 16, 1f));
    }

    @Test
    public void cardWidthNeverGoesBelowTheMinimum() {
        int width = LibraryGridColumns.cardWidthPx(200, 2, 16, 2f);
        assertEquals(Math.round(LibraryGridColumns.MIN_CARD_DP * 2f), width);
        assertTrue(width >= 280);
    }
}
