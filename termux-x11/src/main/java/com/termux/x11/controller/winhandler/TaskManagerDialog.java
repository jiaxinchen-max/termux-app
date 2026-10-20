package com.termux.x11.controller.winhandler;

import android.app.Activity;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;

import com.termux.x11.LorieViewRuntimeApi;
import com.termux.x11.R;
import com.termux.x11.controller.contentdialog.ContentDialog;
import com.termux.x11.controller.core.CPUStatus;

/**
 * Task manager shown as a dialog (terminal + LorieView mode, and the default for every host). The
 * list/dashboard/refresh logic lives in {@link TaskManagerPanel}, which the games drawer embeds too.
 */
public class TaskManagerDialog extends ContentDialog {
    private final Activity activity;
    private final TaskManagerPanel panel;
    private final View.OnLayoutChangeListener windowResizeListener = (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) applySizing();
    };

    public TaskManagerDialog(LorieViewRuntimeApi.WinHandlerHost host) {
        super(host.getActivity(), R.layout.task_manager_dialog, host.getDialogPalette());
        this.activity = host.getActivity();
        setCancelable(false);
        setTitle(R.string.task_manager);
        setIcon(R.drawable.icon_task_manager);

        panel = new TaskManagerPanel(host, findViewById(R.id.LLTaskManagerRoot), getPalette(),
            new TaskManagerPanel.Listener() {
                @Override
                public void onProcessCountChanged(int count) {
                    setBottomBarText(activity.getString(R.string.processes) + ": " + count);
                }

                @Override
                public void onBringToFront() {
                    dismiss();
                }
            });

        Button cancelButton = findViewById(R.id.BTCancel);
        cancelButton.setText(R.string.new_task);
        cancelButton.setOnClickListener((v) -> {
            dismiss();
            panel.showNewTaskPrompt();
        });
        // The window gets an explicit size (applySizing), so the content host must fill it.
        findViewById(R.id.FrameLayout).getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
        // 8dp between "New Task" and "OK" in both layouts.
        View okButton = findViewById(R.id.BTConfirm);
        ViewGroup.LayoutParams okParams = okButton.getLayoutParams();
        if (okParams instanceof ViewGroup.MarginLayoutParams) {
            ((ViewGroup.MarginLayoutParams) okParams).leftMargin = Math.round(TaskManagerDialogSizing.BUTTON_GAP_DP * activity.getResources().getDisplayMetrics().density);
            okButton.setLayoutParams(okParams);
        }

        setOnDismissListener((dialog) -> {
            activity.getWindow().getDecorView().removeOnLayoutChangeListener(windowResizeListener);
            panel.stop();
        });
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
        panel.applyLayout(result);
    }

    @Override
    public void show() {
        applySizing();
        activity.getWindow().getDecorView().addOnLayoutChangeListener(windowResizeListener);
        panel.start();
        super.show();
    }
}
