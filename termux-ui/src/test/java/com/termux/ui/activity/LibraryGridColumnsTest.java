package com.termux.ui.activity;

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

    @Test
    public void importCardIsTheOnlyCellOfAnEmptyLibrary() {
        int index = LibraryGridColumns.importCardIndex(0);
        assertEquals(0, index);
        assertEquals(0, LibraryGridColumns.columnOf(index, 2));
        assertEquals(0, LibraryGridColumns.rowOf(index, 2));
    }

    @Test
    public void importCardFollowsTheLastGameInTheSameRowWhenACellIsFree() {
        // 3 games in 2 columns: games at (0,0) (0,1) (1,0); Import takes the free (1,1).
        int index = LibraryGridColumns.importCardIndex(3);
        assertEquals(3, index);
        assertEquals(1, LibraryGridColumns.columnOf(index, 2));
        assertEquals(1, LibraryGridColumns.rowOf(index, 2));
    }

    @Test
    public void importCardWrapsToTheFirstColumnOfANewRowWhenTheLastRowIsFull() {
        // 4 games in 2 columns fill two rows; Import starts the third row, one cell wide.
        int index = LibraryGridColumns.importCardIndex(4);
        assertEquals(0, LibraryGridColumns.columnOf(index, 2));
        assertEquals(2, LibraryGridColumns.rowOf(index, 2));
    }

    @Test
    public void importCardPositionFollowsTheColumnCountAfterARotation() {
        int games = 5;
        int index = LibraryGridColumns.importCardIndex(games);
        // portrait, 2 columns: row 2, column 1
        assertEquals(2, LibraryGridColumns.rowOf(index, 2));
        assertEquals(1, LibraryGridColumns.columnOf(index, 2));
        // landscape, 3 columns: row 1, column 2
        assertEquals(1, LibraryGridColumns.rowOf(index, 3));
        assertEquals(2, LibraryGridColumns.columnOf(index, 3));
        // landscape, 6 columns: row 0, column 5 (still the last cell)
        assertEquals(0, LibraryGridColumns.rowOf(index, 6));
        assertEquals(5, LibraryGridColumns.columnOf(index, 6));
    }

    @Test
    public void singleColumnPutsEveryItemInItsOwnRowWithImportLast() {
        int index = LibraryGridColumns.importCardIndex(2);
        assertEquals(0, LibraryGridColumns.columnOf(index, 1));
        assertEquals(2, LibraryGridColumns.rowOf(index, 1));
    }

    @Test
    public void cellHelpersAreSafeForADegenerateColumnCount() {
        assertEquals(0, LibraryGridColumns.columnOf(3, 0));
        assertEquals(3, LibraryGridColumns.rowOf(3, 0));
    }
}
