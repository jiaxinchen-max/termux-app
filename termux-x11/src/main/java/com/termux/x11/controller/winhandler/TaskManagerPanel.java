package com.termux.x11.controller.winhandler;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
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
