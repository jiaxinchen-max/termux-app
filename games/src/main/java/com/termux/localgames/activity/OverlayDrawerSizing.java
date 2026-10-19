package com.termux.localgames.activity;

/** Pure sizing rules for the in-game control drawer; px/dp arithmetic only. */
final class OverlayDrawerSizing {

    static final int MAX_DRAWER_WIDTH_DP = 460;
    static final int WINDOW_MARGIN_DP = 16;
    static final int COMPACT_MAX_WIDTH_DP = 560;

    private OverlayDrawerSizing() {}

    /** min(460dp, window width - margin), never below zero. */
    static int drawerWidthPx(int windowWidthPx, float density) {
        int max = Math.round(MAX_DRAWER_WIDTH_DP * density);
        int available = windowWidthPx - Math.round(WINDOW_MARGIN_DP * density);
        return Math.max(0, Math.min(max, available));
    }

    /** True for narrow or portrait-shaped windows: navigation becomes a top strip. */
    static boolean compactNavigation(int windowWidthDp, int windowHeightDp) {
        return windowWidthDp < COMPACT_MAX_WIDTH_DP || windowWidthDp < windowHeightDp;
    }
}
