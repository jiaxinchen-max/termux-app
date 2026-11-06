package com.termux.ui.activity;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.domain.RuntimeSetupTaskState;
import com.termux.view.TerminalView;

import java.io.IOException;

/** Modal TerminalView attached to the actual Termux GLIBC prefix setup session -- the
 *  PrefixSetupTask counterpart of {@link RuntimeSetupConsoleDialog}. Structurally identical
 *  (same golden-ratio sizing, same terminal attach/refresh/dismiss-on-complete flow, same host
 *  terminal lookup keyed purely by taskId); only the pre-terminal-attach status read differs,
 *  since GLIBC prefix tasks live in their own repository and domain type. */
public final class PrefixSetupConsoleDialog extends Dialog {
    private final String taskId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TerminalSession consoleSession;
    private TerminalView terminalView;
    private TextView status;
    private SetupPreparationConsole preparation;
    private boolean dismissed;
    private boolean setupFailed;
    private long sessionPollStartedAtMs;

    private PrefixSetupConsoleDialog(@NonNull Context context, @NonNull String taskId) {
        super(context);
        this.taskId = taskId;
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    public static PrefixSetupConsoleDialog show(@NonNull Context context, @NonNull String taskId) {
        PrefixSetupConsoleDialog dialog = new PrefixSetupConsoleDialog(context, taskId);
        dialog.show();
        return dialog;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        FrostedWindow.clipToPanel(content, getContext());
        content.setContentDescription(
            getContext().getString(R.string.local_games_prefix_setup_console_title));

        status = new TextView(getContext());
        status.setTextColor(ContextCompat.getColor(getContext(),
            R.color.local_games_terminal_secondary));
        status.setTextSize(13);
        status.setText(R.string.local_games_runtime_setup_preparing);
        status.setPadding(dp(8), dp(8), dp(8), 0);
        content.addView(status, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        preparation = new SetupPreparationConsole(getContext(), taskId, false, message -> {
            setupFailed = true;
            applyTheme();
            status.setVisibility(android.view.View.VISIBLE);
            status.setText(getContext().getString(R.string.local_games_runtime_setup_failed) + "\n" + message);
        });
        content.addView(preparation, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        terminalView = new TerminalView(getContext(), null);
        terminalView.setVisibility(android.view.View.GONE);
        com.termux.ui.platform.UiTerminalTheme.apply(terminalView);
        terminalView.setTerminalViewClient(new TermuxTerminalViewClientBase());
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getContext());
        terminalView.setTextSize(preferences == null ? 14 : preferences.getFontSize());
        content.addView(terminalView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Re-fit the golden box when the configuration (rotation, split screen) changes while open.
        ConfigurationAwareFrameLayout root = new ConfigurationAwareFrameLayout(getContext());
        root.addView(content, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setListener(configuration -> {
            applyTheme();
            applyWindowSize();
        });
        setContentView(root);

        Window window = getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            applyTheme();
        }
    }

    private static final double PHI = 1.6180339887;
    private static final double MAX_SHORT_FRACTION = 0.92;

    @Override
    protected void onStart() {
        super.onStart();
        applyTheme();
        applyWindowSize();
        terminalView.post(this::attachSetupSession);
        handler.post(this::refreshStatus);
        preparation.start();
    }

    private void applyTheme() {
        ConsoleDialogTheme.applySetup(this, terminalView, preparation, status, setupFailed);
    }

    private void applyWindowSize() {
        Window window = getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = getContext().getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        boolean landscape = screenWidth >= screenHeight;
        int screenLong = landscape ? screenWidth : screenHeight;
        int screenShort = landscape ? screenHeight : screenWidth;
        int dialogLong = (int) (screenLong / PHI);
        int dialogShort = (int) (dialogLong / PHI);
        if (dialogShort > screenShort * MAX_SHORT_FRACTION) {
            dialogShort = (int) (screenShort * MAX_SHORT_FRACTION);
            dialogLong = (int) (dialogShort * PHI);
        }
        int width = landscape ? dialogLong : dialogShort;
        int height = landscape ? dialogShort : dialogLong;
        // Never larger than the window minus margins, however small the window is.
        window.setLayout(DialogSizing.clamp(width, DialogSizing.maxWidthPx(screenWidth, metrics.density)),
            DialogSizing.clamp(height, DialogSizing.maxHeightPx(screenHeight, metrics.density)));
    }

    @Override
    public void dismiss() {
        dismissed = true;
        if (preparation != null) preparation.stop();
        handler.removeCallbacksAndMessages(null);
        super.dismiss();
    }

    private void attachSetupSession() {
        if (dismissed || !isShowing() || setupFailed) return;
        if (terminalView == null || terminalView.mRenderer == null) {
            handler.postDelayed(this::attachSetupSession, 50);
            return;
        }
        TerminalSession session = LocalGames.requireHost(getContext())
            .getRuntimeSetupTerminal(taskId);
        if (session == null) {
            if (sessionPollStartedAtMs == 0) sessionPollStartedAtMs = System.currentTimeMillis();
            // No live session yet. If the task already reached a terminal state, one will never
            // appear -- a fast, effectively no-op setup (e.g. switching to a Native backend whose
            // GLIBC prefix is already provisioned) finishes before its session is ever observable.
            // Without this, the poll loops forever and the dialog hangs on a blank black terminal.
            // The Library screen surfaces success/failure on its own, so just close.
            PrefixSetupTask task = readTask();
            if (setupFailed) return;
            if (task != null && task.getState() == RuntimeSetupTaskState.SUCCEEDED) {
                complete();
                return;
            }
            // A missing task/session must remain visible as a startup failure.
            if (task == null
                && System.currentTimeMillis() - sessionPollStartedAtMs > 10_000) {
                showFailure("setup_session_unavailable");
                return;
            }
            handler.postDelayed(this::attachSetupSession, 100);
            return;
        }
        consoleSession = session;
        preparation.terminalAttached();
        terminalView.setVisibility(android.view.View.VISIBLE);
        status.setVisibility(android.view.View.GONE);
        terminalView.attachSession(session);
        refreshTerminal();
    }

    private void refreshTerminal() {
        if (dismissed || terminalView == null || consoleSession == null) return;
        terminalView.onScreenUpdated();
        if (consoleSession.isRunning()) {
            handler.postDelayed(this::refreshTerminal, 100);
        } else {
            PrefixSetupTask task = readTask();
            if (setupFailed) return;
            if (consoleSession.getExitStatus() != 0 || (task != null &&
                (task.getState() == RuntimeSetupTaskState.FAILED || task.getState() == RuntimeSetupTaskState.CANCELLED))) {
                showFailure(task == null ? "setup_process_failed" : task.getErrorCode());
            } else if (task != null && task.getState() != RuntimeSetupTaskState.SUCCEEDED) {
                handler.postDelayed(this::refreshTerminal, 500);
            } else {
                handler.postDelayed(this::complete, 1500);
            }
        }
    }

    private void refreshStatus() {
        if (dismissed || !isShowing() || consoleSession != null || setupFailed) return;
        PrefixSetupTask task = readTask();
        if (setupFailed) return;
        String preparing = getContext().getString(R.string.local_games_runtime_setup_preparing);
        if (task == null) {
            status.setText(preparing);
        } else if (task.getState() == RuntimeSetupTaskState.FAILED) {
            showFailure(task.getErrorCode());
            return;
        } else if (task.getState() == RuntimeSetupTaskState.CANCELLED) {
            setupFailed = true;
            status.setText(RuntimeSetupTaskState.CANCELLED.name());
            return;
        } else if (task.getState() == RuntimeSetupTaskState.SUCCEEDED) {
            // The task finished before its terminal session ever became observable -- a fast,
            // effectively no-op prefix setup (e.g. the prefix was already provisioned, so the
            // session opened and closed between status polls). attachSetupSession() would
            // otherwise keep polling getRuntimeSetupTerminal() forever for a session that no
            // longer exists, leaving this dialog stuck on a blank black terminal. There is
            // nothing left to show, so close -- mirroring refreshTerminal()'s dismiss-on-finish.
            complete();
            return;
        } else {
            status.setText(preparing + " (" + task.getState().name() + ")");
        }
        handler.postDelayed(this::refreshStatus, 500);
    }

    private PrefixSetupTask readTask() {
        try {
            return new FilePrefixSetupTaskRepository(new GameStoragePaths(getContext()
                .getFilesDir()).getPrefixSetupTasksDirectory()).find(taskId).orElse(null);
        } catch (IOException | RuntimeException error) {
            showFailure("task_read_failed: " + error.getMessage());
            return null;
        }
    }

    private void complete() {
        if (dismissed || setupFailed) return;
        android.widget.Toast.makeText(getContext(), R.string.local_games_preparation_completed,
            android.widget.Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private void showFailure(String message) {
        setupFailed = true;
        applyTheme();
        status.setVisibility(android.view.View.VISIBLE);
        status.setText(getContext().getString(R.string.local_games_runtime_setup_failed) + "\n" + message);
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
