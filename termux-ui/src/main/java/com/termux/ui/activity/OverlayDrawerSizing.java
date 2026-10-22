package com.termux.ui.activity;

/** Pure sizing rules for the in-game control drawer; px/dp arithmetic only. */
final class OverlayDrawerSizing {

    static final int MAX_DRAWER_WIDTH_DP = 460;
    static final int WINDOW_MARGIN_DP = 16;
    static final int COMPACT_MAX_WIDTH_DP = 560;
    /** A bottom sheet never covers more than this share of the height the drawer layer offers. */
    static final float BOTTOM_SHEET_MAX_HEIGHT_FRACTION = 0.85f;

    /** Where the drawer panel sits and which way it slides. */
    enum Placement {
        /** Full-height column on the right edge; slides in along X (wide / landscape windows). */
        SIDE,
        /** Full-width sheet on the bottom edge; slides in along Y (narrow / portrait windows). */
        BOTTOM_SHEET
    }

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

    /** Compact (narrow or portrait) windows get a bottom sheet, everything else the side drawer. */
    static Placement placementFor(int windowWidthDp, int windowHeightDp) {
        return compactNavigation(windowWidthDp, windowHeightDp)
            ? Placement.BOTTOM_SHEET : Placement.SIDE;
    }

    /** Height cap of a bottom sheet: about 85% of the available height, never below zero. */
    static int bottomSheetMaxHeightPx(int availableHeightPx) {
        return Math.max(0, Math.round(availableHeightPx * BOTTOM_SHEET_MAX_HEIGHT_FRACTION));
    }
}
