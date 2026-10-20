package com.termux.x11.controller.winhandler;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.x11.LorieViewRuntimeApi;
import com.termux.x11.R;
import com.termux.x11.controller.contentdialog.ContentDialog;
import com.termux.x11.controller.contentdialog.DialogPalette;
import com.termux.x11.controller.core.CPUStatus;
import com.termux.x11.controller.core.ProcessHelper;
import com.termux.x11.controller.core.StringUtils;
import com.termux.x11.controller.winhandler.metrics.MetricChartView;
import com.termux.x11.controller.winhandler.metrics.MetricSeries;
import com.termux.x11.controller.winhandler.metrics.PerformanceMetricsSampler;
import com.termux.x11.controller.widget.CPUListView;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Task manager content (dashboard + process list + 1s refresh) shared by {@link TaskManagerDialog}
 * and hosts that embed it in their own UI (the games drawer sub-page). The panel owns the process
 * collection/refresh logic; the hosts only decide where the view lives, which
 * {@link TaskManagerDialogSizing.Result} to apply and what to do with the process count and with
 * "Bring to front". {@link #start()} starts the refresh timer, {@link #stop()} always cancels it.
 */
public final class TaskManagerPanel implements OnGetProcessInfoListener {

    /** Host callbacks; always invoked on the UI thread. */
    public interface Listener {
        /** The number of listed processes changed (drives the "Processes: N" footer). */
        void onProcessCountChanged(int count);

        /** The user chose "Bring to front" for a process. */
        void onBringToFront();
    }

    private final LorieViewRuntimeApi.WinHandlerHost host;
    private final Activity activity;
    private final LayoutInflater inflater;
    private final View root;
    @Nullable
    private final DialogPalette palette;
    private final Listener listener;
    private final Object lock = new Object();
    private Timer timer;
    private boolean started;
    private int refreshSequence;
    private int processInfoSequence;
    private TaskManagerDialogSizing.Result sizing;
    private boolean pageScroll;
    /** Games host only: the 60 s history; null keeps the dialog's text-only dashboard. */
    @Nullable
    private PerformanceMetricsSampler metrics;
    private boolean metricsExpanded;
    private static final int CHART_HEIGHT_DP = 96;
    private ChartBlock fpsChart;
    private ChartBlock cpuChart;
    private ChartBlock memoryChart;

    /** One chart of the expanded dashboard: title, latest value and the line chart. */
    private static final class ChartBlock {
        final LinearLayout box;
        final TextView title;
        final TextView value;
        final MetricChartView chart;

        ChartBlock(LinearLayout box, TextView title, TextView value, MetricChartView chart) {
            this.box = box;
            this.title = title;
            this.value = value;
            this.chart = chart;
        }
    }

    /** Inflates the panel layout into {@code parent} (not attached) for use with the constructor. */
    @NonNull
    public static View inflateContent(@NonNull Context context, @Nullable ViewGroup parent) {
        return LayoutInflater.from(context).inflate(R.layout.task_manager_panel, parent, false);
    }

    /**
     * @param root a view inflated from the task manager panel layout (directly through
     *             {@link #inflateContent} or through a layout that includes it)
     */
    public TaskManagerPanel(@NonNull LorieViewRuntimeApi.WinHandlerHost host, @NonNull View root,
                            @Nullable DialogPalette palette, @NonNull Listener listener) {
        this.host = host;
        this.activity = host.getActivity();
        this.inflater = LayoutInflater.from(activity);
        this.root = root;
        this.palette = palette;
        this.listener = listener;
        applyPalette();
        find(R.id.BTMetricsToggle).setOnClickListener(v -> setMetricsExpanded(!metricsExpanded));
    }

    private <T extends View> T find(int id) {
        return root.findViewById(id);
    }

    public boolean isStarted() {
        return started;
    }

    /** Applies a layout; call again whenever the available size changes (rotation re-flows in place). */
    public void applyLayout(@NonNull TaskManagerDialogSizing.Result result) {
        applyLayout(result, false);
    }

    /**
     * Applies a layout. With {@code pageScroll} the panel is laid out at its full natural height
     * (dashboard, then every process row; no inner scrolling or height cap) so that the host can put
     * it into a single vertical scroll container. Otherwise it fills its container and the process
     * list scrolls on its own. The process rows are never rebuilt by switching, so a scroll position
     * survives the switch and the 1s refresh.
     */
    public void applyLayout(@NonNull TaskManagerDialogSizing.Result result, boolean pageScroll) {
        float density = activity.getResources().getDisplayMetrics().density;
        sizing = result;
        this.pageScroll = pageScroll;
        applyLayoutMode(result, density);

        final LinearLayout container = find(R.id.LLProcessList);
        for (int i = 0; i < container.getChildCount(); i++) applyColumnWidths(container.getChildAt(i), density);
        setColumnWidth(find(R.id.THPid), result.pidColumnDp, density);
        setColumnWidth(find(R.id.THMemory), result.memoryColumnDp, density);
    }

    /**
     * Host switch: enables the Expand/Collapse dashboard (latest FPS line, 60 s charts) over the
     * history held by {@code sampler}. Pass null to disable. The sampler is fed by the host
     * independently of this panel's refresh timer, so the charts already have history when the
     * page is first opened. Collapsed (text) is the default.
     */
    public void setMetricsSampler(@Nullable PerformanceMetricsSampler sampler) {
        if (metrics != null) metrics.setListener(null);
        metrics = sampler;
        if (sampler != null) {
            ensureCharts();
            sampler.setListener(this::onMetricsChanged);
        }
        find(R.id.LLMetricsHeader).setVisibility(sampler != null ? View.VISIBLE : View.GONE);
        applyMetricsMode();
    }

    public boolean isMetricsExpanded() {
        return metricsExpanded;
    }

    /** Shows the charts (true) or the text cards (false); the host persists this across recreation. */
    public void setMetricsExpanded(boolean expanded) {
        metricsExpanded = expanded;
        applyMetricsMode();
    }

    /** Releases the sampler listener; call when the panel is dropped. */
    public void release() {
        if (metrics != null) metrics.setListener(null);
    }

    /** Starts listening for process info and refreshing once per second. */
    public void start() {
        if (started) return;
        // Throws while the WinHandler is not attached; nothing has changed by then.
        final WinHandler winHandler = host.getWinHandler();
        started = true;
        update();
        winHandler.setOnGetProcessInfoListener(this);

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                activity.runOnUiThread(TaskManagerPanel.this::update);
            }
        }, 0, 1000);
    }

    /** Cancels the refresh timer and releases the process listener. Safe to call repeatedly. */
    public void stop() {
        boolean wasStarted = started;
        started = false;
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (!wasStarted) return;
        host.getTermuxProcessorInfo("1");
        final LinearLayout container = find(R.id.LLProcessList);
        container.removeAllViews();
        host.getWinHandler().setOnGetProcessInfoListener(null);
    }

    /** Asks for a command to run in the container ("New Task"). */
    public void showNewTaskPrompt() {
        ContentDialog.prompt(activity, R.string.new_task, "taskmgr.exe", palette,
            (command) -> host.getWinHandler().exec(command));
    }

    private void applyLayoutMode(TaskManagerDialogSizing.Result result, float density) {
        LinearLayout rootLayout = find(R.id.LLTaskManagerRoot);
        View table = find(R.id.LLTablePanel);
        ScrollView info = find(R.id.SVInfoPanel);
        LinearLayout infoInner = find(R.id.LLInfoPanel);
        View cpuPanel = find(R.id.LLCpuPanel);
        View memoryPanel = find(R.id.LLMemoryPanel);
        int gap = Math.round(8 * density);
        applyPageScroll(rootLayout, table, density);

        LinearLayout.LayoutParams tableParams;
        LinearLayout.LayoutParams infoParams;
        LinearLayout.LayoutParams cpuParams;
        LinearLayout.LayoutParams memoryParams;
        if (result.stackInfoPanel) {
            rootLayout.setOrientation(LinearLayout.VERTICAL);
            tableParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            // In page-scroll mode the dashboard is shown at its natural height and scrolls with the page.
            infoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    pageScroll ? ViewGroup.LayoutParams.WRAP_CONTENT : Math.round(result.infoPanelHeightDp * density));
            infoParams.bottomMargin = gap;
            if (result.cardsSideBySide) {
                infoInner.setOrientation(LinearLayout.HORIZONTAL);
                cpuParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 3f);
                memoryParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 2f);
                memoryParams.leftMargin = gap;
            } else {
                infoInner.setOrientation(LinearLayout.VERTICAL);
                cpuParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                memoryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                memoryParams.topMargin = gap;
            }
            if (rootLayout.indexOfChild(info) != 0) {
                rootLayout.removeView(info);
                rootLayout.addView(info, 0);
            }
        } else {
            rootLayout.setOrientation(LinearLayout.HORIZONTAL);
            tableParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            infoParams = new LinearLayout.LayoutParams(Math.round(TaskManagerDialogSizing.WIDE_INFO_PANEL_WIDTH_DP * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            infoParams.leftMargin = gap;
            infoInner.setOrientation(LinearLayout.VERTICAL);
            cpuParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            memoryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            memoryParams.topMargin = gap;
            if (rootLayout.indexOfChild(info) != 1) {
                rootLayout.removeView(info);
                rootLayout.addView(info, 1);
            }
        }
        table.setLayoutParams(tableParams);
        info.setLayoutParams(infoParams);
        cpuPanel.setLayoutParams(cpuParams);
        memoryPanel.setLayoutParams(memoryParams);
        // The dashboard is always shown in full (CPU % + per-core MHz, memory % + used/total).
        find(R.id.LLCPUInfo).setVisibility(View.VISIBLE);
        updateCPUInfoView();
    }

    /**
     * Page-scroll mode: the panel root and the table take their natural height and the process rows
     * move out of the list's own ScrollView into its frame (the same container view, so rows and
     * their state are kept); the inner ScrollView is hidden. The inverse restores list scrolling.
     */
    private void applyPageScroll(LinearLayout rootLayout, View table, float density) {
        ViewGroup.LayoutParams rootParams = rootLayout.getLayoutParams();
        if (rootParams != null) {
            int height = pageScroll ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT;
            if (rootParams.height != height) {
                rootParams.height = height;
                rootLayout.setLayoutParams(rootParams);
            }
        }
        FrameLayout frame = find(R.id.FLProcessListFrame);
        ScrollView listScroll = find(R.id.SVProcessList);
        LinearLayout list = find(R.id.LLProcessList);
        if (pageScroll) {
            if (list.getParent() == listScroll) {
                listScroll.removeView(list);
                frame.addView(list, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            listScroll.setVisibility(View.GONE);
            // Keeps the empty-state text visible when there are no rows to give the frame a height.
            frame.setMinimumHeight(Math.round(2 * TaskManagerDialogSizing.ROW_HEIGHT_DP * density));
            LinearLayout.LayoutParams frameParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            frame.setLayoutParams(frameParams);
        } else {
            if (list.getParent() == frame) {
                frame.removeView(list);
                listScroll.addView(list, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            listScroll.setVisibility(View.VISIBLE);
            frame.setMinimumHeight(0);
            frame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
    }

    private void applyColumnWidths(View row, float density) {
        if (sizing == null) return;
        setColumnWidth(row.findViewById(R.id.TVPID), sizing.pidColumnDp, density);
        setColumnWidth(row.findViewById(R.id.TVMemoryUsage), sizing.memoryColumnDp, density);
    }

    private static void setColumnWidth(View column, int widthDp, float density) {
        if (column == null) return;
        ViewGroup.LayoutParams params = column.getLayoutParams();
        int widthPx = Math.round(widthDp * density);
        if (params != null && params.width != widthPx) {
            params.width = widthPx;
            column.setLayoutParams(params);
        }
    }

    private void applyRowStyle(View row) {
        applyColumnWidths(row, activity.getResources().getDisplayMetrics().density);
        if (palette == null) return;
        ((TextView) row.findViewById(R.id.TVName)).setTextColor(palette.onSurface);
        ((TextView) row.findViewById(R.id.TVPID)).setTextColor(palette.onSurface);
        ((TextView) row.findViewById(R.id.TVMemoryUsage)).setTextColor(palette.onSurface);
        ((ImageView) row.findViewById(R.id.BTMenu)).setColorFilter(palette.accent);
    }

    private GradientDrawable panelBackground(int fill, float radiusDp) {
        float density = activity.getResources().getDisplayMetrics().density;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radiusDp * density);
        drawable.setStroke(Math.max(1, Math.round(density)), palette.outline);
        return drawable;
    }

    private void applyPalette() {
        if (palette == null) return;
        float radius = Math.min(palette.cornerRadiusDp, 6f);
        find(R.id.LLTablePanel).setBackground(panelBackground(palette.panel, radius));
        find(R.id.LLCpuPanel).setBackground(panelBackground(palette.panel, radius));
        find(R.id.LLMemoryPanel).setBackground(panelBackground(palette.panel, radius));
        int[] headIds = {R.id.THName, R.id.THPid, R.id.THMemory, R.id.THMenu};
        for (int id : headIds) {
            View head = find(id);
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(palette.tableHead);
            fill.setCornerRadius(3 * activity.getResources().getDisplayMetrics().density);
            head.setBackground(fill);
            if (head instanceof TextView) ((TextView) head).setTextColor(palette.tableHeadText);
        }
        ((TextView) find(R.id.TVCPUTitle)).setTextColor(palette.accent);
        ((TextView) find(R.id.TVMemoryTitle)).setTextColor(palette.accent);
        ((TextView) find(R.id.TVEmptyText)).setTextColor(palette.onSurfaceSecondary);
        ((TextView) find(R.id.TVMemoryInfo)).setTextColor(palette.onSurface);
        ((TextView) find(R.id.TVFpsInfo)).setTextColor(palette.onSurface);
        ((TextView) find(R.id.TVMetricsToggle)).setTextColor(palette.accent);
        ((ImageView) find(R.id.IVMetricsToggle)).setColorFilter(palette.accent);
        GradientDrawable toggle = new GradientDrawable();
        toggle.setColor(Color.TRANSPARENT);
        toggle.setCornerRadius(Math.min(palette.cornerRadiusDp, 6f) * activity.getResources().getDisplayMetrics().density);
        toggle.setStroke(Math.max(1, Math.round(activity.getResources().getDisplayMetrics().density)), palette.accent);
        find(R.id.BTMetricsToggle).setBackground(toggle);
    }

    private void ensureCharts() {
        if (fpsChart != null) return;
        LinearLayout container = find(R.id.LLChartsPanel);
        fpsChart = createChartBlock(container, activity.getString(R.string.metrics_fps), false, false);
        cpuChart = createChartBlock(container, "CPU", true, true);
        memoryChart = createChartBlock(container, activity.getString(R.string.memory), true, true);
    }

    private ChartBlock createChartBlock(LinearLayout container, String titleText, boolean percent, boolean topGap) {
        float density = activity.getResources().getDisplayMetrics().density;
        int pad = Math.round(8 * density);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        if (palette != null) box.setBackground(panelBackground(palette.panel, Math.min(palette.cornerRadiusDp, 6f)));
        else box.setBackgroundResource(R.drawable.bordered_panel);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView title = new TextView(activity);
        title.setText(titleText);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        title.setTextColor(palette != null ? palette.accent : activity.getColor(R.color.colorPrimary));
        row.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = new TextView(activity);
        value.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        if (palette != null) value.setTextColor(palette.onSurface);
        row.addView(value, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        MetricChartView chart = new MetricChartView(activity);
        if (palette != null) {
            chart.setLineColor(palette.accent);
            chart.setGridColor((palette.outline & 0x00FFFFFF) | 0x66000000);
            chart.setTextColor(palette.onSurfaceSecondary);
        }
        LinearLayout.LayoutParams chartParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Math.round(CHART_HEIGHT_DP * density));
        chartParams.topMargin = Math.round(4 * density);
        box.addView(chart, chartParams);

        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topGap) boxParams.topMargin = Math.round(8 * density);
        container.addView(box, boxParams);
        return new ChartBlock(box, title, value, chart);
    }

    /** Shows either the text cards (collapsed) or the charts (expanded) and updates the toggle. */
    private void applyMetricsMode() {
        boolean enabled = metrics != null;
        boolean expanded = enabled && metricsExpanded;
        find(R.id.LLInfoPanel).setVisibility(expanded ? View.GONE : View.VISIBLE);
        find(R.id.LLChartsPanel).setVisibility(expanded ? View.VISIBLE : View.GONE);
        find(R.id.TVFpsInfo).setVisibility(expanded ? View.INVISIBLE : View.VISIBLE);
        ((TextView) find(R.id.TVMetricsToggle)).setText(expanded ? R.string.metrics_collapse : R.string.metrics_expand);
        ((ImageView) find(R.id.IVMetricsToggle)).setImageResource(
            expanded ? R.drawable.ic_metrics_collapse : R.drawable.ic_metrics_expand);
        if (enabled) onMetricsChanged();
    }

    /** Sampler callback (UI thread): refreshes the FPS line and, when shown, the charts. */
    private void onMetricsChanged() {
        PerformanceMetricsSampler sampler = metrics;
        if (sampler == null) return;
        MetricSeries fps = sampler.fps();
        String fpsText = formatFps(fps.latest());
        ((TextView) find(R.id.TVFpsInfo)).setText(activity.getString(R.string.metrics_fps_line, fpsText));
        if (!metricsExpanded || fpsChart == null) return;
        fpsChart.chart.bind(fps, false);
        fpsChart.value.setText(Double.isNaN(fps.latest()) ? fpsText : activity.getString(R.string.metrics_fps_value, fpsText));
        cpuChart.chart.bind(sampler.cpu(), true);
        cpuChart.value.setText(formatPercent(sampler.cpu()));
        memoryChart.chart.bind(sampler.memory(), true);
        String memory = formatPercent(sampler.memory());
        if (sampler.memoryTotalBytes() > 0) {
            memory += " \u00b7 " + StringUtils.formatBytes(sampler.memoryUsedBytes(), false)
                + "/" + StringUtils.formatBytes(sampler.memoryTotalBytes());
        }
        memoryChart.value.setText(memory);
    }

    private String formatFps(double value) {
        return Double.isNaN(value) ? activity.getString(R.string.metrics_unavailable) : String.valueOf(Math.round(value));
    }

    private String formatPercent(MetricSeries series) {
        double latest = series.latest();
        return Double.isNaN(latest) ? activity.getString(R.string.metrics_unavailable) : Math.round(latest) + "%";
    }

    private void update() {
        if (!started) return;
        final int refresh;
        synchronized (lock) {
            refresh = ++refreshSequence;
            host.getWinHandler().listProcesses();

            final LinearLayout container = find(R.id.LLProcessList);
            if (container.getChildCount() == 0) {
                listAndroidProcess();
                if (container.getChildCount() == 0) {
                    find(R.id.TVEmptyText).setVisibility(View.VISIBLE);
                }
            }
        }

        find(R.id.LLProcessList).postDelayed(() -> clearStaleProcessList(refresh), 800);
        updateCPUInfoView();
        updateMemoryInfoView();
    }

    private void clearStaleProcessList(int refresh) {
        if (!started) return;
        synchronized (lock) {
            if (refreshSequence != refresh || processInfoSequence >= refresh) {
                return;
            }

            final LinearLayout container = find(R.id.LLProcessList);
            // Update rows in place (applyProcessInfo trims extras) so a long list keeps its scroll
            // position; only an empty result clears the rows.
            List<ProcessInfo> processInfoList = host.getTermuxProcessorInfo("0");
            if (processInfoList == null || processInfoList.isEmpty()) {
                container.removeAllViews();
                find(R.id.TVEmptyText).setVisibility(View.VISIBLE);
                listener.onProcessCountChanged(0);
            } else {
                listAndroidProcess(processInfoList);
            }
        }
    }

    private void listAndroidProcess() {
        listAndroidProcess(host.getTermuxProcessorInfo("0"));
    }

    private void listAndroidProcess(@Nullable List<ProcessInfo> processInfoList) {
        if (processInfoList == null) {
            return;
        }
        int idx = 0;
        for (ProcessInfo processInfo : processInfoList) {
            applyProcessInfo(idx, processInfoList.size(), processInfo);
            idx++;
        }
    }

    private void showListItemMenu(final View anchorView, final ProcessInfo processInfo) {
        PopupMenu listItemMenu = new PopupMenu(activity, anchorView);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

        listItemMenu.inflate(R.menu.process_popup_menu);
        listItemMenu.setOnMenuItemClickListener((menuItem) -> {
            int itemId = menuItem.getItemId();
            final WinHandler winHandler = host.getWinHandler();
            if (itemId == R.id.process_affinity) {
                showProcessorAffinityDialog(processInfo);
            } else if (itemId == R.id.bring_to_front) {
                winHandler.bringToFront(processInfo.name);
                listener.onBringToFront();
            } else if (itemId == R.id.process_end) {
                ContentDialog.confirm(activity, R.string.do_you_want_to_end_this_process, palette, () -> {
                    winHandler.killProcess(processInfo.name);
                });
            }
            return true;
        });
        listItemMenu.show();
    }

    private void showProcessorAffinityDialog(final ProcessInfo processInfo) {
        ContentDialog dialog = new ContentDialog(activity, R.layout.cpu_list_dialog, palette);
        dialog.setTitle(processInfo.name);
        dialog.setIcon(R.drawable.icon_cpu);
        final CPUListView cpuListView = dialog.findViewById(R.id.CPUListView);
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        cpuListView.getLayoutParams().width = Math.round(TaskManagerDialogSizing.subDialogContentWidthDp(metrics.widthPixels / metrics.density) * metrics.density);
        cpuListView.setCheckedCPUList(processInfo.getCPUList());
        dialog.setOnConfirmCallback(() -> {
            WinHandler winHandler = host.getWinHandler();
            winHandler.setProcessAffinity(processInfo.pid, ProcessHelper.getAffinityMask(cpuListView.getCheckedCPUList()));
            update();
        });
        dialog.show();
    }

    @Override
    public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        activity.runOnUiThread(() -> {
            if (!started) return;
            synchronized (lock) {
                applyProcessInfo(index, numProcesses, processInfo);
            }
        });
    }

    private void applyProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        processInfoSequence = refreshSequence;

        final LinearLayout container = find(R.id.LLProcessList);
        listener.onProcessCountChanged(numProcesses);

        if (numProcesses == 0) {
            container.removeAllViews();
            find(R.id.TVEmptyText).setVisibility(View.VISIBLE);
            return;
        }

        find(R.id.TVEmptyText).setVisibility(View.GONE);

        int childCount = container.getChildCount();
        View itemView;
        if (index < childCount) {
            itemView = container.getChildAt(index);
        } else {
            itemView = inflater.inflate(R.layout.process_info_list_item, container, false);
            applyRowStyle(itemView);
        }
        ((TextView) itemView.findViewById(R.id.TVName)).setText(processInfo.name + (processInfo.wow64Process ? " *32" : ""));
        ((TextView) itemView.findViewById(R.id.TVPID)).setText(String.valueOf(processInfo.pid));
        ((TextView) itemView.findViewById(R.id.TVMemoryUsage)).setText(processInfo.getFormattedMemoryUsage());
        itemView.findViewById(R.id.BTMenu).setOnClickListener((v) -> showListItemMenu(v, processInfo));

        ImageView ivIcon = itemView.findViewById(R.id.IVIcon);
        if (ivIcon != null)
            ivIcon.setImageResource(R.drawable.taskmgr_process);

        if (index >= childCount) container.addView(itemView);

        if (index == numProcesses - 1 && childCount > numProcesses) {
            for (int i = childCount - 1; i >= numProcesses; i--) container.removeViewAt(i);
        }
    }

    private void updateCPUInfoView() {
        LinearLayout llCPUInfo = find(R.id.LLCPUInfo);
        llCPUInfo.removeAllViews();
        short[] clockSpeeds = CPUStatus.getCurrentClockSpeeds();
        if (clockSpeeds.length == 0) return;
        int totalClockSpeed = 0;
        short maxClockSpeed = 0;

        int columns = sizing != null ? Math.max(1, sizing.cpuGridColumns) : 1;
        LinearLayout gridRow = null;
        for (int i = 0; i < clockSpeeds.length; i++) {
            if (i % columns == 0) {
                gridRow = new LinearLayout(activity);
                gridRow.setOrientation(LinearLayout.HORIZONTAL);
                llCPUInfo.addView(gridRow, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            TextView textView = new TextView(activity);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            if (palette != null) textView.setTextColor(palette.onSurface);
            short clockSpeed = CPUStatus.getMaxClockSpeed(i);
            textView.setText(clockSpeeds[i] + "/" + clockSpeed + " MHz");
            gridRow.addView(textView, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            totalClockSpeed += clockSpeeds[i];
            maxClockSpeed = (short) Math.max(maxClockSpeed, clockSpeed);
        }

        int avgClockSpeed = totalClockSpeed / clockSpeeds.length;
        TextView tvCPUTitle = find(R.id.TVCPUTitle);
        byte cpuUsagePercent = (byte) (((float) avgClockSpeed / maxClockSpeed) * 100.0f);
        tvCPUTitle.setText("CPU (" + cpuUsagePercent + "%)");
    }

    private void updateMemoryInfoView() {
        ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        byte memUsagePercent = (byte) (((double) usedMem / memoryInfo.totalMem) * 100.0f);

        TextView tvMemoryTitle = find(R.id.TVMemoryTitle);
        tvMemoryTitle.setText(activity.getString(R.string.memory) + " (" + memUsagePercent + "%)");

        TextView tvMemoryInfo = find(R.id.TVMemoryInfo);
        tvMemoryInfo.setText(StringUtils.formatBytes(usedMem, false) + "/" + StringUtils.formatBytes(memoryInfo.totalMem));
    }
}
