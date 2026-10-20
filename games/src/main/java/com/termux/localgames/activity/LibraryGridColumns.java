package com.termux.localgames.activity;

/**
 * Column count and card width of the Library game grid. Both orientations are driven by the width
 * the grid really has (phones, tablets, split-screen slices), not by a fixed
 * number per orientation.
 */
final class LibraryGridColumns {

    /** Narrowest comfortable portrait card, including its margins (the portrait card is a compact
     *  vertical card). */
    static final int PORTRAIT_MIN_CELL_DP = 160;
    /** Landscape cards are the wider cover-first cards (220 dp) plus spacing. */
    static final int LANDSCAPE_MIN_CELL_DP = 236;
    static final int PORTRAIT_MAX_COLUMNS = 6;
    static final int LANDSCAPE_MAX_COLUMNS = 6;
    /** A card is never narrower than this. */
    static final int MIN_CARD_DP = 140;

    private LibraryGridColumns() {}

    /** Portrait keeps at least two columns (the card design is a half-width card); landscape may
     *  fall back to a single column in a very narrow window. */
    static int columnsFor(int availableWidthDp, boolean landscape) {
        if (landscape) {
            int columns = availableWidthDp / LANDSCAPE_MIN_CELL_DP;
            return Math.max(1, Math.min(LANDSCAPE_MAX_COLUMNS, columns));
        }
        int columns = availableWidthDp / PORTRAIT_MIN_CELL_DP;
        return Math.max(2, Math.min(PORTRAIT_MAX_COLUMNS, columns));
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
