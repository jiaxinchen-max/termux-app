package com.termux.ui.activity;

/** Pure column/tile-width arithmetic for the file manager grid (no Android classes). */
final class FileManagerGridColumns {

    /** Smallest cell width including both side margins. */
    static final int MIN_CELL_DP = 72;
    /** Portrait is designed for four columns per row (fewer only if the window is too narrow). */
    static final int PORTRAIT_COLUMNS = 4;
    static final int LANDSCAPE_MAX_COLUMNS = 10;
    /** List mode goes to two rows per line once this much width is available (landscape only). */
    static final int LIST_TWO_COLUMN_DP = 560;

    private FileManagerGridColumns() {}

    /** Number of columns for a grid that is {@code availableWidthDp} wide (padding excluded).
     *  Never returns a count whose cells would be narrower than {@link #MIN_CELL_DP}, except that
     *  at least one column is always returned. */
    static int columnsFor(int availableWidthDp, boolean landscape) {
        int fit = Math.max(1, availableWidthDp / MIN_CELL_DP);
        if (landscape) {
            return Math.min(LANDSCAPE_MAX_COLUMNS, fit);
        }
        return Math.min(PORTRAIT_COLUMNS, fit);
    }

    /** Columns of list mode: one row per line, two on a wide landscape window. */
    static int listColumnsFor(int availableWidthDp, boolean landscape) {
        return landscape && availableWidthDp >= LIST_TWO_COLUMN_DP ? 2 : 1;
    }

    /** Width of a tile (cell width minus both margins) so that {@code columns} cells fill the
     *  available width without overflowing. */
    static int tileWidthPx(int availableWidthPx, int columns, int marginPx) {
        int safeColumns = Math.max(1, columns);
        return Math.max(1, availableWidthPx / safeColumns - 2 * marginPx);
    }
}
