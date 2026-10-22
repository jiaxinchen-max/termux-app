package com.termux.ui.activity;

/**
 * Orientation-aware size limits shared by every self-sized dialog window, so a dialog never grows
 * past the window it is shown in. Pure arithmetic (pixels in, pixels out) so it can be unit tested.
 *
 * <p>The window can be a phone in portrait (about 360 dp wide), a phone in landscape (about 360 dp
 * high), a tablet, or a split-screen slice, hence every limit is derived from the current window
 * size instead of from a fixed dp value.
 */
final class DialogSizing {

    /** Space kept free around a dialog on every side. */
    static final int MARGIN_DP = 16;
    /** Dialogs never get wider than this, however large the window is. */
    static final int MAX_WIDTH_DP = 560;
    /** A bounded list inside a dialog (file chooser) never gets shorter than this. */
    static final int MIN_LIST_HEIGHT_DP = 120;
    /** A bounded list inside a dialog never gets taller than this. */
    static final int MAX_LIST_HEIGHT_DP = 300;
    /** Console dialogs aim for at least this height when the window allows it. */
    static final int MIN_CONSOLE_HEIGHT_DP = 280;

    private DialogSizing() {}

    static boolean isLandscape(int widthPx, int heightPx) {
        return widthPx > heightPx;
    }

    static int dpToPx(int dp, float density) {
        return Math.round(dp * density);
    }

    /** Widest dialog that fits the window with {@link #MARGIN_DP} on both sides, at most
     *  {@link #MAX_WIDTH_DP}. */
    static int maxWidthPx(int windowWidthPx, float density) {
        int available = Math.max(0, windowWidthPx - 2 * dpToPx(MARGIN_DP, density));
        return Math.min(available, dpToPx(MAX_WIDTH_DP, density));
    }

    /** Tallest dialog that fits the window with {@link #MARGIN_DP} above and below. */
    static int maxHeightPx(int windowHeightPx, float density) {
        return Math.max(0, windowHeightPx - 2 * dpToPx(MARGIN_DP, density));
    }

    /** {@code desired} limited to {@code max}; a non-positive {@code max} means "no room", not
     *  "unbounded", and yields 0. */
    static int clamp(int desiredPx, int maxPx) {
        return Math.max(0, Math.min(desiredPx, maxPx));
    }

    /** Fraction of the window used by a dialog, but never beyond the margin limits. */
    static int fractionOfWidthPx(int windowWidthPx, float fraction, float density) {
        return clamp(Math.round(windowWidthPx * fraction), maxWidthPx(windowWidthPx, density));
    }

    static int fractionOfHeightPx(int windowHeightPx, float fraction, float density) {
        return clamp(Math.round(windowHeightPx * fraction), maxHeightPx(windowHeightPx, density));
    }

    /**
     * Height of a scrolling list embedded in a dialog: {@link #MAX_LIST_HEIGHT_DP} on tall windows,
     * about 45 % of the window height otherwise, never below {@link #MIN_LIST_HEIGHT_DP}, so the
     * title, input and buttons around it stay on screen in a 360 dp high landscape window.
     */
    static int listHeightPx(int windowHeightPx, float density) {
        int byWindow = Math.round(windowHeightPx * 0.45f);
        int preferred = Math.min(dpToPx(MAX_LIST_HEIGHT_DP, density), byWindow);
        return Math.max(dpToPx(MIN_LIST_HEIGHT_DP, density), preferred);
    }

    /**
     * Height of a console dialog: 64 % of the window, raised to {@link #MIN_CONSOLE_HEIGHT_DP} when
     * that is smaller, but never beyond the window minus its margins.
     */
    static int consoleHeightPx(int windowHeightPx, float density) {
        int wanted = Math.max(Math.round(windowHeightPx * 0.64f),
            dpToPx(MIN_CONSOLE_HEIGHT_DP, density));
        return clamp(wanted, maxHeightPx(windowHeightPx, density));
    }

    /**
     * Left edge of a popup of {@code popupWidthPx} whose right edge lines up with the anchor's right
     * edge, kept inside the window with {@code marginPx} on both sides.
     */
    static int popupLeftPx(int anchorRightPx, int popupWidthPx, int windowWidthPx, int marginPx) {
        int preferred = anchorRightPx - popupWidthPx;
        int highest = Math.max(marginPx, windowWidthPx - popupWidthPx - marginPx);
        return Math.max(marginPx, Math.min(preferred, highest));
    }

    /**
     * Top edge of a popup of {@code popupHeightPx}: above the anchor when it fits there, otherwise
     * below it when that fits, otherwise as high as the window allows (the popup is bounded to the
     * window by the caller, so it never runs off either end).
     */
    static int popupTopPx(int anchorTopPx, int anchorBottomPx, int popupHeightPx,
                          int windowHeightPx, int marginPx) {
        int above = anchorTopPx - popupHeightPx;
        if (above >= marginPx) return above;
        if (anchorBottomPx + popupHeightPx <= windowHeightPx - marginPx) return anchorBottomPx;
        return Math.max(marginPx, Math.min(above, windowHeightPx - popupHeightPx - marginPx));
    }
}
