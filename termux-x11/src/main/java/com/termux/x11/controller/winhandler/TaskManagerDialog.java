package com.termux.x11.controller.winhandler;

import android.app.ActivityManager;
import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Window;
import android.widget.ScrollView;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

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

public class TaskManagerDialog extends ContentDialog implements OnGetProcessInfoListener {
    private final LorieViewRuntimeApi.WinHandlerHost host;
    private final Activity activity;
    private final LayoutInflater inflater;
    private Timer timer;
    private final Object lock = new Object();
    private int refreshSequence;
    private int processInfoSequence;
    private TaskManagerDialogSizing.Result sizing;
    private final DialogPalette palette;
    private final View.OnLayoutChangeListener windowResizeListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applySizing();
    };

    public TaskManagerDialog(LorieViewRuntimeApi.WinHandlerHost host) {
        super(host.getActivity(), R.layout.task_manager_dialog, host.getDialogPalette());
        this.host = host;
        this.palette = getPalette();
        this.activity = host.getActivity();
        setCancelable(false);
        setTitle(R.string.task_manager);
        setIcon(R.drawable.icon_task_manager);

        Button cancelButton = findViewById(R.id.BTCancel);
        cancelButton.setText(R.string.new_task);
        cancelButton.setOnClickListener((v) -> {
            dismiss();
            ContentDialog.prompt(activity, R.string.new_task, "taskmgr.exe", palette, (command) -> host.getWinHandler().exec(command));
        });
        // The window gets an explicit size (applySizing), so the content host must fill it.
        findViewById(R.id.FrameLayout).getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        applyTaskManagerPalette();
        // 8dp between "New Task" and "OK" in both layouts.
        View okButton = findViewById(R.id.BTConfirm);
        ViewGroup.LayoutParams okParams = okButton.getLayoutParams();
        if (okParams instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) okParams).leftMargin = Math.round(TaskManagerDialogSizing.BUTTON_GAP_DP * activity.getResources().getDisplayMetrics().density);
            okButton.setLayoutParams(okParams);
        }

        setOnDismissListener((dialog) -> {
            activity.getWindow().getDecorView().removeOnLayoutChangeListener(windowResizeListener);
            if (timer != null) {
                timer.cancel();
                timer = null;
            }
            host.getTermuxProcessorInfo("1");
            final LinearLayout container = findViewById(R.id.LLProcessList);
            container.removeAllViews();
            host.getWinHandler().setOnGetProcessInfoListener(null);
        });
        inflater = LayoutInflater.from(activity);
    }


    /** Recomputes the dialog size from the current window size and re-flows the layout. */
    private void applySizing() {
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        View decor = activity.getWindow().getDecorView();
        int windowWidthPx = decor.getWidth() > 0 ? decor.getWidth() : metrics.widthPixels;
        int windowHeightPx = decor.getHeight() > 0 ? decor.getHeight() : metrics.heightPixels;
        float density = metrics.density;
        TaskManagerDialogSizing.Result result = TaskManagerDialogSizing.compute(windowWidthPx / density, windowHeightPx / density, CPUStatus.getCurrentClockSpeeds().length);

        Window window = getWindow();
        if (window != null) {
            window.setLayout(Math.round(result.widthDp * density), Math.round(result.heightDp * density));
        }
        sizing = result;
        applyLayoutMode(result, density);

        final LinearLayout container = findViewById(R.id.LLProcessList);
        for (int i = 0; i < container.getChildCount(); i++) applyColumnWidths(container.getChildAt(i), density);
        setColumnWidth(findViewById(R.id.THPid), result.pidColumnDp, density);
        setColumnWidth(findViewById(R.id.THMemory), result.memoryColumnDp, density);
    }

    private void applyLayoutMode(TaskManagerDialogSizing.Result result, float density) {
        LinearLayout root = findViewById(R.id.LLTaskManagerRoot);
        View table = findViewById(R.id.LLTablePanel);
        ScrollView info = findViewById(R.id.SVInfoPanel);
        LinearLayout infoInner = findViewById(R.id.LLInfoPanel);
        View cpuPanel = findViewById(R.id.LLCpuPanel);
        View memoryPanel = findViewById(R.id.LLMemoryPanel);
        int gap = Math.round(8 * density);

        LinearLayout.LayoutParams tableParams;
        LinearLayout.LayoutParams infoParams;
        LinearLayout.LayoutParams cpuParams;
        LinearLayout.LayoutParams memoryParams;
        if (result.stackInfoPanel) {
            root.setOrientation(LinearLayout.VERTICAL);
            tableParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            infoParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    Math.round(result.infoPanelHeightDp * density));
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
            if (root.indexOfChild(info) != 0) {
                root.removeView(info);
                root.addView(info, 0);
            }
        } else {
            root.setOrientation(LinearLayout.HORIZONTAL);
            tableParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            infoParams = new LinearLayout.LayoutParams(Math.round(TaskManagerDialogSizing.WIDE_INFO_PANEL_WIDTH_DP * density), ViewGroup.LayoutParams.WRAP_CONTENT);
            infoParams.leftMargin = gap;
            infoInner.setOrientation(LinearLayout.VERTICAL);
            cpuParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            memoryParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            memoryParams.topMargin = gap;
            if (root.indexOfChild(info) != 1) {
                root.removeView(info);
                root.addView(info, 1);
            }
        }
        table.setLayoutParams(tableParams);
        info.setLayoutParams(infoParams);
        cpuPanel.setLayoutParams(cpuParams);
        memoryPanel.setLayoutParams(memoryParams);
        // The dashboard is always shown in full (CPU % + per-core MHz, memory % + used/total).
        findViewById(R.id.LLCPUInfo).setVisibility(View.VISIBLE);
        updateCPUInfoView();
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

    private void applyTaskManagerPalette() {
        if (palette == null) return;
        float radius = Math.min(palette.cornerRadiusDp, 6f);
        findViewById(R.id.LLTablePanel).setBackground(panelBackground(palette.panel, radius));
        findViewById(R.id.LLCpuPanel).setBackground(panelBackground(palette.panel, radius));
        findViewById(R.id.LLMemoryPanel).setBackground(panelBackground(palette.panel, radius));
        int[] headIds = {R.id.THName, R.id.THPid, R.id.THMemory, R.id.THMenu};
        for (int id : headIds) {
            View head = findViewById(id);
            GradientDrawable fill = new GradientDrawable();
            fill.setColor(palette.tableHead);
            fill.setCornerRadius(3 * activity.getResources().getDisplayMetrics().density);
            head.setBackground(fill);
            if (head instanceof TextView) ((TextView) head).setTextColor(palette.tableHeadText);
        }
        ((TextView) findViewById(R.id.TVCPUTitle)).setTextColor(palette.accent);
        ((TextView) findViewById(R.id.TVMemoryTitle)).setTextColor(palette.accent);
        ((TextView) findViewById(R.id.TVEmptyText)).setTextColor(palette.onSurfaceSecondary);
    }

    private void update() {
        final int refresh;
        synchronized (lock) {
            refresh = ++refreshSequence;
            host.getWinHandler().listProcesses();

            final LinearLayout container = findViewById(R.id.LLProcessList);
            if (container.getChildCount() == 0) {
                listAndroidProcess();
                if (container.getChildCount() == 0) {
                    findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
                }
            }

        }

        findViewById(R.id.LLProcessList).postDelayed(() -> clearStaleProcessList(refresh), 800);
        updateCPUInfoView();
        updateMemoryInfoView();
    }

    private void clearStaleProcessList(int refresh) {
        synchronized (lock) {
            if (refreshSequence != refresh || processInfoSequence >= refresh) {
                return;
            }

            final LinearLayout container = findViewById(R.id.LLProcessList);
            container.removeAllViews();
            listAndroidProcess();
            if (container.getChildCount() == 0) {
                findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
                setBottomBarText(activity.getString(R.string.processes) + ": 0");
            }
        }
    }

    private void listAndroidProcess() {
        List<ProcessInfo> processInfoList = host.getTermuxProcessorInfo("0");
        if (processInfoList == null) {
            return;
        }
        int idx = 0;
        for (ProcessInfo processInfo : processInfoList) {
            applyProcessInfo(idx, processInfoList.size(), processInfo);
            idx++;
        }
//        ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
//        List<ActivityManager.RunningAppProcessInfo> runningAppProcessInfoList = activityManager.getRunningAppProcesses();
//        int idx = 0;
//        for (ActivityManager.RunningAppProcessInfo process : runningAppProcessInfoList) {
//            ProcessInfo processInfo = new ProcessInfo(process.pid, process.processName, activityManager.getProcessMemoryInfo(new int[]{process.pid})[0].getTotalPrivateDirty(), 15, true);
//            onGetProcessInfo(idx, runningAppProcessInfoList.size(), processInfo);
//            idx++;
//        }
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
                dismiss();
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
    public void show() {
        applySizing();
        activity.getWindow().getDecorView().addOnLayoutChangeListener(windowResizeListener);
        update();
        host.getWinHandler().setOnGetProcessInfoListener(this);

        timer = new Timer();
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                activity.runOnUiThread(TaskManagerDialog.this::update);
            }
        }, 0, 1000);
        super.show();
    }

    @Override
    public void onGetProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        activity.runOnUiThread(() -> {
            synchronized (lock) {
                applyProcessInfo(index, numProcesses, processInfo);
            }
        });
    }

    private void applyProcessInfo(int index, int numProcesses, ProcessInfo processInfo) {
        processInfoSequence = refreshSequence;

        final LinearLayout container = findViewById(R.id.LLProcessList);
        setBottomBarText(activity.getString(R.string.processes) + ": " + numProcesses);

        if (numProcesses == 0) {
            container.removeAllViews();
            findViewById(R.id.TVEmptyText).setVisibility(View.VISIBLE);
            return;
        }

        findViewById(R.id.TVEmptyText).setVisibility(View.GONE);

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
        LinearLayout llCPUInfo = findViewById(R.id.LLCPUInfo);
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
        TextView tvCPUTitle = findViewById(R.id.TVCPUTitle);
        byte cpuUsagePercent = (byte) (((float) avgClockSpeed / maxClockSpeed) * 100.0f);
        tvCPUTitle.setText("CPU (" + cpuUsagePercent + "%)");
    }

    private void updateMemoryInfoView() {
        ActivityManager activityManager = (ActivityManager) activity.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        long usedMem = memoryInfo.totalMem - memoryInfo.availMem;
        byte memUsagePercent = (byte) (((double) usedMem / memoryInfo.totalMem) * 100.0f);

        TextView tvMemoryTitle = findViewById(R.id.TVMemoryTitle);
        tvMemoryTitle.setText(activity.getString(R.string.memory) + " (" + memUsagePercent + "%)");

        TextView tvMemoryInfo = findViewById(R.id.TVMemoryInfo);
        tvMemoryInfo.setText(StringUtils.formatBytes(usedMem, false) + "/" + StringUtils.formatBytes(memoryInfo.totalMem));
    }
}
