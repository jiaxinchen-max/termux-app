package com.termux.localgames.activity;

import com.termux.x11.controller.winhandler.TaskManagerDialogSizing;

/**
 * Pure layout rules for the "Process manager" sub-page of the control drawer. In both drawer forms
 * (bottom sheet and right-side column, any window height) the page is a vertical stack, dashboard on
 * top and process list below, and always scrolls as one page (dashboard + full-height list); only the
 * footer may stay fixed ({@link Result#footerFixed}). The dialog's
 * 430+140dp side-by-side layout is never used here; the dashboard/column maths is
 * {@link TaskManagerDialogSizing#computeEmbedded}. dp arithmetic only, so it runs on the JVM.
 */
final class DrawerProcessPageSizing {

    /** Outer drawer padding on every side (R.dimen.local_games_overlay_padding). */
    static final int DRAWER_PADDING_DP = 12;
    /** Header row (back arrow, title, Exit, close) plus its gap to the page. */
    static final int HEADER_DP = 48 + 14;
    /** Footer row (Processes: N and New Task) including its top gap. */
    static final int FOOTER_DP = 48 + 8;
    /** Room reserved for system bars the drawer layer is padded by. */
    static final int SYSTEM_INSETS_DP = 48;
    /** The page never gets less than this much height for the panel. */
    static final int MIN_PANEL_HEIGHT_DP = 160;
    /** Below this window height the footer scrolls at the end of the page instead of staying fixed. */
    static final int FIXED_FOOTER_MIN_WINDOW_HEIGHT_DP = 480;

    static final class Result {
        /** Width available to the panel inside the drawer. */
        final int panelWidthDp;
        /** Height available to the panel (between header and footer). */
        final int panelHeightDp;
        /** Stacked task-manager layout for exactly that size. */
        final TaskManagerDialogSizing.Result layout;
        /** True when the page needs this explicit height (bottom sheet wraps its content). */
        final boolean explicitHeight;
        /**
         * Whether the footer (Processes count + New Task) stays fixed below the page. False only for
         * false in short windows, where it scrolls at the end of the content.
         */
        final boolean footerFixed;

        Result(int panelWidthDp, int panelHeightDp, TaskManagerDialogSizing.Result layout,
               boolean explicitHeight, boolean footerFixed) {
            this.footerFixed = footerFixed;
            this.panelWidthDp = panelWidthDp;
            this.panelHeightDp = panelHeightDp;
            this.layout = layout;
            this.explicitHeight = explicitHeight;
        }
    }

    private DrawerProcessPageSizing() {}

    static Result compute(int windowWidthDp, int windowHeightDp,
                          OverlayDrawerSizing.Placement placement, int cpuCores) {
        int width = Math.max(0, windowWidthDp);
        int height = Math.max(0, windowHeightDp);
        int chrome = 2 * DRAWER_PADDING_DP + HEADER_DP + FOOTER_DP;
        int drawerWidth;
        int panelHeight;
        boolean sheet = placement == OverlayDrawerSizing.Placement.BOTTOM_SHEET;
        if (sheet) {
            drawerWidth = width;
            int sheetHeight = Math.round(
                Math.max(0, height - SYSTEM_INSETS_DP) * OverlayDrawerSizing.BOTTOM_SHEET_MAX_HEIGHT_FRACTION);
            panelHeight = sheetHeight - chrome;
        } else {
            drawerWidth = Math.max(0, Math.min(OverlayDrawerSizing.MAX_DRAWER_WIDTH_DP,
                width - OverlayDrawerSizing.WINDOW_MARGIN_DP));
            panelHeight = height - SYSTEM_INSETS_DP - chrome;
        }
        int panelWidth = Math.max(0, drawerWidth - 2 * DRAWER_PADDING_DP);
        boolean footerFixed = height >= FIXED_FOOTER_MIN_WINDOW_HEIGHT_DP;
        panelHeight = Math.max(MIN_PANEL_HEIGHT_DP, panelHeight);
        return new Result(panelWidth, panelHeight,
            TaskManagerDialogSizing.computeEmbedded(panelWidth, panelHeight, cpuCores), sheet,
            footerFixed);
    }
}
