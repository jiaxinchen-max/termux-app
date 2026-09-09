package com.termux.localgames.activity;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.termux.localgames.R;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;
import com.termux.localgames.service.ComponentTaskConsoleLog;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Non-cancelable foreground log surface for one durable component installation. */
final class ComponentTaskConsoleDialog extends Dialog {
    private static final int MAX_LOG_CHARS = 48 * 1024;

    private final String taskId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView output;
    private TextView state;
    private ProgressBar progress;
    private MaterialButton hide;
    private boolean dismissed;

    private ComponentTaskConsoleDialog(@NonNull Context context, @NonNull String taskId) {
        super(context);
        if (!taskId.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("task_id_invalid");
        this.taskId = taskId;
        setCancelable(false);
        setCanceledOnTouchOutside(false);
    }

    static void show(@NonNull Context context, @NonNull String taskId) {
        new ComponentTaskConsoleDialog(context, taskId).show();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        int padding = dp(18);
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);
        content.setBackgroundColor(Color.rgb(12, 16, 27));

        TextView title = new TextView(getContext());
        title.setText(R.string.local_games_component_console_title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        content.addView(title);

        state = new TextView(getContext());
        state.setTextColor(Color.rgb(170, 185, 210));
        state.setTextSize(13);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stateParams.topMargin = dp(8);
        content.addView(state, stateParams);

        progress = new ProgressBar(getContext(), null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(true);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        progressParams.topMargin = dp(10);
        content.addView(progress, progressParams);

        output = new TextView(getContext());
        output.setTextColor(Color.WHITE);
        output.setTextSize(13);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setBackgroundColor(Color.BLACK);
        output.setPadding(dp(10), dp(10), dp(10), dp(10));
        output.setMovementMethod(new ScrollingMovementMethod());
        LinearLayout.LayoutParams outputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        outputParams.topMargin = dp(12);
        content.addView(output, outputParams);

        hide = new MaterialButton(getContext());
        hide.setText(R.string.local_games_component_console_hide);
        hide.setOnClickListener(view -> dismiss());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.gravity = Gravity.END;
        actionParams.topMargin = dp(8);
        content.addView(hide, actionParams);
        setContentView(content);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window == null) return;
        int width = Math.round(getContext().getResources().getDisplayMetrics().widthPixels * .82f);
        int height = Math.round(getContext().getResources().getDisplayMetrics().heightPixels * .64f);
        window.setLayout(width, height);
    }

    @Override
    public void dismiss() {
        dismissed = true;
        handler.removeCallbacksAndMessages(null);
        super.dismiss();
    }

    private void refresh() {
        if (dismissed || !isShowing()) return;
        ComponentTask task = readTask();
        output.setText(readLog());
        boolean terminal = task == null || isFinished(task.getState());
        if (task == null) {
            state.setText(R.string.local_games_component_console_missing);
            progress.setIndeterminate(false);
            progress.setProgress(0);
        } else {
            state.setText(ComponentTaskConsoleLog.stateLine(task));
            if (task.getExpectedSize() > 0) {
                progress.setIndeterminate(false);
                progress.setProgress((int) Math.min(100L,
                    task.getDownloadedBytes() * 100L / task.getExpectedSize()));
            } else {
                progress.setIndeterminate(!terminal);
                progress.setProgress(terminal ? 100 : 0);
            }
        }
        hide.setText(terminal ? R.string.local_games_runtime_provision_console_close
            : R.string.local_games_component_console_hide);
        if (!terminal) handler.postDelayed(this::refresh, 500);
    }

    private ComponentTask readTask() {
        try {
            return new FileComponentTaskRepository(new ComponentStoragePaths(getContext()
                .getFilesDir()).getTasksDirectory()).find(taskId).orElse(null);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private String readLog() {
        File file = ComponentTaskConsoleLog.file(getContext().getFilesDir(), taskId);
        if (!file.isFile()) return getContext().getString(R.string.local_games_component_console_waiting);
        StringBuilder text = new StringBuilder();
        try (BufferedReader input = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = input.readLine()) != null) {
                if (text.length() + line.length() + 1 > MAX_LOG_CHARS) {
                    text.delete(0, Math.min(text.length(), 8192));
                }
                text.append(line).append('\n');
            }
        } catch (IOException error) {
            return error.getMessage() == null ? "log_unavailable" : error.getMessage();
        }
        return text.length() == 0 ? getContext().getString(
            R.string.local_games_component_console_waiting) : text.toString();
    }

    private static boolean isFinished(ComponentTaskState state) {
        return state == ComponentTaskState.INSTALLED || state == ComponentTaskState.CANCELLED ||
            state == ComponentTaskState.FAILED || state == ComponentTaskState.PAUSED;
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
