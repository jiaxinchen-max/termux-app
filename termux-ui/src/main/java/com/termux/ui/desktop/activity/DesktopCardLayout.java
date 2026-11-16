package com.termux.ui.desktop.activity;

/** Compact desktop cards keep their content width, rather than stretching to fill a row. */
public final class DesktopCardLayout {
    public static final int WIDTH_DP = 128;
    public static final int GAP_DP = 6;

    private DesktopCardLayout() { }

    public static int widthDp(int availableDp, float fontScale) {
        int preferred = Math.round(WIDTH_DP * Math.max(1f, fontScale));
        return Math.max(1, Math.min(preferred, availableDp - GAP_DP));
    }

    public static int columns(int availableDp, float fontScale) {
        return Math.max(1, availableDp / (widthDp(availableDp, fontScale) + GAP_DP));
    }
}
