package com.termux.localgames.activity;

import android.app.Dialog;
import android.content.Context;
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

import androidx.core.content.ContextCompat;
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
    private LinearLayout content;
    private TextView title;
    private LinearLayout.LayoutParams stateParams;
    private LinearLayout.LayoutParams progressParams;
    private LinearLayout.LayoutParams outputParams;
    private LinearLayout.LayoutParams actionParams;
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
        content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        FrostedWindow.clipToPanel(content, getContext());

        title = new TextView(getContext());
        title.setText(R.string.local_games_component_console_title);
        title.setTextColor(ContextCompat.getColor(getContext(), R.color.local_games_on_surface));
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setTextSize(20);
        content.addView(title);

        state = new TextView(getContext());
        state.setTextColor(ContextCompat.getColor(getContext(),
            R.color.local_games_on_surface_secondary));
        state.setTextSize(13);
        stateParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(state, stateParams);

        progress = new ProgressBar(getContext(), null,
            android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(true);
        progressParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(5));
        content.addView(progress, progressParams);

        output = new TextView(getContext());
        output.setTextColor(ContextCompat.getColor(getContext(), R.color.local_games_terminal_text));
        output.setTextSize(13);
        output.setTypeface(android.graphics.Typeface.MONOSPACE);
        output.setBackground(FrostedWindow.surface(getContext(),
            R.color.local_games_terminal_background, FrostedWindow.ROW_RADIUS_DP));
        output.setPadding(dp(10), dp(10), dp(10), dp(10));
        output.setMovementMethod(new ScrollingMovementMethod());
        outputParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        content.addView(output, outputParams);

        hide = new MaterialButton(getContext());
        hide.setText(R.string.local_games_component_console_hide);
        hide.setOnClickListener(view -> dismiss());
        actionParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.gravity = Gravity.END;
        hide.setMinHeight(dp(48));
        hide.setMinimumHeight(dp(48));
        content.addView(hide, actionParams);
        ConfigurationAwareFrameLayout root = new ConfigurationAwareFrameLayout(getContext());
        root.addView(content, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setListener(configuration -> applyWindowSize());
        setContentView(root);
        applyCompactness();

        Window window = getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            FrostedWindow.applyPanelSurface(window, getContext(), R.color.local_games_console_background);
            FrostedWindow.applyScrim(this);
        }
        refresh();
    }

    @Override
    protected void onStart() {
        super.onStart();
        applyWindowSize();
    }

    private void applyWindowSize() {
        Window window = getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int width = DialogSizing.fractionOfWidthPx(metrics.widthPixels, .86f, metrics.density);
        width = DialogSizing.clamp(width, DialogSizing.maxWidthPx(metrics.widthPixels, metrics.density));
        int height = DialogSizing.consoleHeightPx(metrics.heightPixels, metrics.density);
        window.setLayout(width, height);
        applyCompactness();
    }

    /** Smaller paddings in a short window so the log keeps a usable height. */
    private void applyCompactness() {
        if (content == null) return;
        android.util.DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        boolean compact = metrics.heightPixels < dp(400);
        int padding = dp(compact ? 10 : 18);
        content.setPadding(padding, padding, padding, padding);
        title.setTextSize(compact ? 16 : 20);
        stateParams.topMargin = dp(compact ? 2 : 8);
        progressParams.topMargin = dp(compact ? 4 : 10);
        outputParams.topMargin = dp(compact ? 6 : 12);
        actionParams.topMargin = dp(compact ? 2 : 8);
        content.requestLayout();
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
        hide.setText(terminal ? R.string.local_games_runtime_setup_console_close
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
