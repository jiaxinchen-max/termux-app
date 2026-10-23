package com.termux.ui.activity;

/**
 * Column count and card width of the Library game grid. Both orientations are driven by the width
 * the grid really has (phones, tablets, split-screen slices), not by a fixed
 * number per orientation.
 */
final class LibraryGridColumns {

    /** Narrowest comfortable portrait card, including its margins (the portrait card is a compact
     *  vertical card, half the size it used to be). */
    static final int PORTRAIT_MIN_CELL_DP = 80;
    /** Landscape cards are the cover-first cards (110 dp) plus spacing. */
    static final int LANDSCAPE_MIN_CELL_DP = 118;
    static final int PORTRAIT_MAX_COLUMNS = 12;
    static final int LANDSCAPE_MAX_COLUMNS = 12;
    /** A card is never narrower than this. */
    static final int MIN_CARD_DP = 70;
    /** Portrait keeps at least this many columns. */
    static final int PORTRAIT_MIN_COLUMNS = 4;

    private LibraryGridColumns() {}

    /** Portrait keeps at least {@link #PORTRAIT_MIN_COLUMNS} columns (the card design is a
     *  quarter-width card); landscape may fall back to a single column in a very narrow window. */
    static int columnsFor(int availableWidthDp, boolean landscape) {
        if (landscape) {
            int columns = availableWidthDp / LANDSCAPE_MIN_CELL_DP;
            return Math.max(1, Math.min(LANDSCAPE_MAX_COLUMNS, columns));
        }
        int columns = availableWidthDp / PORTRAIT_MIN_CELL_DP;
        return Math.max(PORTRAIT_MIN_COLUMNS, Math.min(PORTRAIT_MAX_COLUMNS, columns));
    }

    /** Grid column of the item at {@code index} when the grid has {@code columns} columns. */
    static int columnOf(int index, int columns) {
        return index % Math.max(1, columns);
    }

    /** Grid row of the item at {@code index} when the grid has {@code columns} columns. */
    static int rowOf(int index, int columns) {
        return index / Math.max(1, columns);
    }

    /** The Import card is the last grid item: it sits right after the last game card (so it is
     *  the only item of an empty library, and shares the last row with the games when a cell is
     *  free). */
    static int importCardIndex(int gameCount) {
        return Math.max(0, gameCount);
    }

    /** Card width inside a grid {@code gridWidthPx} wide: the column share minus the gutter, never
     *  below {@link #MIN_CARD_DP}. */
    static int cardWidthPx(int gridWidthPx, int columns, int gutterPx, float density) {
        int safeColumns = Math.max(1, columns);
        int share = gridWidthPx / safeColumns - gutterPx;
        return Math.max(Math.round(MIN_CARD_DP * density), share);
    }
}
