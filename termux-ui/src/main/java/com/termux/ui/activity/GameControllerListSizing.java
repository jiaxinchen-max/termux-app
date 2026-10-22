package com.termux.ui.activity;

/**
 * Size of the controller list box on the Controller page: it always shows exactly
 * {@link #VISIBLE_ROWS} rows (a fixed height, so the page below does not move when profiles come
 * and go) and scrolls inside itself when there are more. Pure pixel arithmetic, no Android types.
 */
final class GameControllerListSizing {

    /** Rows the list box shows without scrolling. */
    static final int VISIBLE_ROWS = 4;

    private GameControllerListSizing() { }

    /**
     * Height of the box for {@link #VISIBLE_ROWS} rows: the top padding plus, per row, its minimum
     * height and the gap below it (the last gap doubles as the bottom padding).
     */
    static int viewportHeightPx(int rowMinHeightPx, int rowGapPx, int paddingPx) {
        return paddingPx + VISIBLE_ROWS * (rowMinHeightPx + rowGapPx);
    }

    /** True when the profiles do not fit the box and it has to scroll. */
    static boolean scrolls(int profileCount) {
        return profileCount > VISIBLE_ROWS;
    }
}
