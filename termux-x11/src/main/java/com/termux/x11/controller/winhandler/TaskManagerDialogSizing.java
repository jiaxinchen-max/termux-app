package com.termux.x11.controller.winhandler;

/**
 * Pure sizing rules for the task manager dialog. Input is the usable window size in dp, output is
 * the dialog size, whether the CPU/memory panel is stacked above the process table, and the
 * process-row column widths. No Android dependencies so it can be unit tested on the JVM.
 */
public final class TaskManagerDialogSizing {
    public static final int EDGE_MARGIN_DP = 16;
    /** Dialog widths below this stack the CPU/memory panel above the process table. */
    public static final int COMPACT_BREAKPOINT_DP = 600;
    public static final int MAX_WIDE_WIDTH_DP = 720;
    public static final int MAX_COMPACT_WIDTH_DP = 560;
    public static final int MAX_HEIGHT_DP = 640;
    public static final int MIN_WIDTH_DP = 240;
    public static final int MIN_HEIGHT_DP = 200;
    public static final float HEIGHT_FRACTION = 0.85f;
    public static final int MAX_SUB_DIALOG_CONTENT_WIDTH_DP = 360;
    public static final int MIN_SUB_DIALOG_CONTENT_WIDTH_DP = 200;
    public static final int ROW_HEIGHT_DP = 40;
    public static final int MENU_BUTTON_DP = 40;
    public static final int BUTTON_GAP_DP = 8;
    /** Fixed width of the CPU/memory column in the side-by-side layout. */
    public static final int WIDE_INFO_PANEL_WIDTH_DP = 140;
    /** Estimated dialog chrome (title bar and bottom bar) and minimum process table height (5 rows). */
    public static final int DIALOG_CHROME_DP = 120;
    public static final int MIN_TABLE_PANEL_DP = 16 + 30 + 5 * ROW_HEIGHT_DP;
    public static final int MIN_INFO_PANEL_DP = 72;
    public static final int CARD_PADDING_DP = 16;
    public static final int CARD_TITLE_DP = 22;
    public static final int CPU_LINE_DP = 20;
    public static final int CARD_GAP_DP = 8;
    /** Stacked widths from this value on place the CPU and memory cards next to each other. */
    public static final int CARDS_SIDE_BY_SIDE_MIN_WIDTH_DP = 340;
    /** Stacked widths from this value on lay the per-core list out in two columns. */
    public static final int CPU_TWO_COLUMN_MIN_WIDTH_DP = 300;
    public static final int DEFAULT_CPU_CORES = 8;

    public static final class Result {
        public final int widthDp;
        public final int heightDp;
        public final boolean stackInfoPanel;
        public final int pidColumnDp;
        public final int memoryColumnDp;
        /** The CPU (title, percentage, per-core current/max MHz) and memory cards are always fully shown. */
        public final boolean fullDashboard = true;
        /** Number of columns of the per-core frequency grid. */
        public final int cpuGridColumns;
        /** Stacked mode only: CPU and memory cards share one row instead of being stacked. */
        public final boolean cardsSideBySide;
        /** Stacked mode: height of the dashboard area (scrolls when content is taller). 0 = wrap content. */
        public final int infoPanelHeightDp;

        Result(int widthDp, int heightDp, boolean stackInfoPanel, int pidColumnDp, int memoryColumnDp,
               int cpuGridColumns, boolean cardsSideBySide, int infoPanelHeightDp) {
            this.cpuGridColumns = cpuGridColumns;
            this.cardsSideBySide = cardsSideBySide;
            this.infoPanelHeightDp = infoPanelHeightDp;
            this.widthDp = widthDp;
            this.heightDp = heightDp;
            this.stackInfoPanel = stackInfoPanel;
            this.pidColumnDp = pidColumnDp;
            this.memoryColumnDp = memoryColumnDp;
        }
    }

    private TaskManagerDialogSizing() {
    }

    public static Result compute(float windowWidthDp, float windowHeightDp) {
        return compute(windowWidthDp, windowHeightDp, DEFAULT_CPU_CORES);
    }

    public static Result compute(float windowWidthDp, float windowHeightDp, int cpuCores) {
        int cores = Math.max(1, cpuCores);
        float w = Math.max(0f, windowWidthDp);
        float h = Math.max(0f, windowHeightDp);

        int availWidth = (int) Math.floor(w - 2 * EDGE_MARGIN_DP);
        int width = Math.min(availWidth, MAX_WIDE_WIDTH_DP);
        boolean stack = width < COMPACT_BREAKPOINT_DP;
        if (stack) width = Math.min(availWidth, MAX_COMPACT_WIDTH_DP);
        width = Math.max(width, Math.min(MIN_WIDTH_DP, (int) Math.floor(w)));

        int availHeight = (int) Math.floor(h - 2 * EDGE_MARGIN_DP);
        int height = Math.min(Math.min((int) Math.floor(h * HEIGHT_FRACTION), availHeight), MAX_HEIGHT_DP);
        height = Math.max(height, Math.min(MIN_HEIGHT_DP, (int) Math.floor(h)));

        int[] columnsDp = columnWidthsDp(stack, width);
        int pid = columnsDp[0];
        int memory = columnsDp[1];

        int columns = 1;
        boolean sideBySide = false;
        int infoHeight = 0;
        if (stack) {
            sideBySide = cardsSideBySide(width);
            columns = cpuGridColumns(width, cores);
            infoHeight = dashboardHeightDp(width, height - DIALOG_CHROME_DP, cores);
        }
        return new Result(width, height, stack, pid, memory, columns, sideBySide, infoHeight);
    }

    /**
     * Layout of the task manager embedded in a host panel (the games drawer sub-page). The panel is
     * always stacked (dashboard above the process list), so only the container size matters: no
     * dialog margins, no dialog chrome. {@code containerHeightDp} is the height left for the panel
     * itself, i.e. without the host's header and footer.
     */
    public static Result computeEmbedded(float containerWidthDp, float containerHeightDp, int cpuCores) {
        int cores = Math.max(1, cpuCores);
        int width = (int) Math.floor(Math.max(0f, containerWidthDp));
        int height = (int) Math.floor(Math.max(0f, containerHeightDp));
        int[] columnsDp = columnWidthsDp(true, width);
        return new Result(width, height, true, columnsDp[0], columnsDp[1],
            cpuGridColumns(width, cores), cardsSideBySide(width), dashboardHeightDp(width, height, cores));
    }

    /** {pid, memory} column widths for a stacked/wide layout of the given panel width. */
    private static int[] columnWidthsDp(boolean stack, int width) {
        if (!stack) return new int[] {60, 110};
        if (width >= 340) return new int[] {52, 84};
        if (width >= 300) return new int[] {44, 72};
        return new int[] {36, 56};
    }

    private static boolean cardsSideBySide(int width) {
        return width >= CARDS_SIDE_BY_SIDE_MIN_WIDTH_DP;
    }

    private static int cpuGridColumns(int width, int cores) {
        return width >= CPU_TWO_COLUMN_MIN_WIDTH_DP && cores > 1 ? 2 : 1;
    }

    /**
     * Height of the scrollable dashboard: its wanted height, but never so tall that fewer than five
     * process rows remain in {@code availableHeightDp} (never below the minimum dashboard height).
     */
    private static int dashboardHeightDp(int width, int availableHeightDp, int cores) {
        int columns = cpuGridColumns(width, cores);
        int rows = (cores + columns - 1) / columns;
        int cpuCard = CARD_PADDING_DP + CARD_TITLE_DP + rows * CPU_LINE_DP;
        int memoryCard = CARD_PADDING_DP + CARD_TITLE_DP + CPU_LINE_DP;
        int wanted = cardsSideBySide(width) ? Math.max(cpuCard, memoryCard) : cpuCard + CARD_GAP_DP + memoryCard;
        int cap = availableHeightDp - MIN_TABLE_PANEL_DP - CARD_GAP_DP;
        return Math.min(wanted, Math.max(MIN_INFO_PANEL_DP, cap));
    }

    /** Width for the content of dialogs opened from the task manager (CPU affinity list, input). */
    public static int subDialogContentWidthDp(float windowWidthDp) {
        int w = (int) Math.floor(Math.max(0f, windowWidthDp) - 4 * EDGE_MARGIN_DP);
        return Math.max(MIN_SUB_DIALOG_CONTENT_WIDTH_DP, Math.min(MAX_SUB_DIALOG_CONTENT_WIDTH_DP, w));
    }
}
