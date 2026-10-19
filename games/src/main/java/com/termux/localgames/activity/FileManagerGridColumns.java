package com.termux.localgames.activity;

/** Pure column/tile-width arithmetic for the file manager grid (no Android classes). */
final class FileManagerGridColumns {

    /** Smallest cell width including both side margins. */
    static final int MIN_CELL_DP = 104;
    /** Portrait switches from 2 to 3 columns once this much grid width is available. */
    static final int PORTRAIT_THREE_COLUMN_DP = 440;
    static final int PORTRAIT_MIN_COLUMNS = 2;
    static final int PORTRAIT_MAX_COLUMNS = 3;
    static final int LANDSCAPE_MAX_COLUMNS = 8;

    private FileManagerGridColumns() {}

    /** Number of columns for a grid that is {@code availableWidthDp} wide (padding excluded).
     *  Never returns a count whose cells would be narrower than {@link #MIN_CELL_DP}, except that
     *  at least one column is always returned. */
    static int columnsFor(int availableWidthDp, boolean landscape) {
        int fit = Math.max(1, availableWidthDp / MIN_CELL_DP);
        if (landscape) {
            return Math.min(LANDSCAPE_MAX_COLUMNS, fit);
        }
        int wanted = availableWidthDp >= PORTRAIT_THREE_COLUMN_DP
            ? PORTRAIT_MAX_COLUMNS : PORTRAIT_MIN_COLUMNS;
        return Math.min(wanted, fit);
    }

    /** Width of a tile (cell width minus both margins) so that {@code columns} cells fill the
     *  available width without overflowing. */
    static int tileWidthPx(int availableWidthPx, int columns, int marginPx) {
        int safeColumns = Math.max(1, columns);
        return Math.max(1, availableWidthPx / safeColumns - 2 * marginPx);
    }
}
