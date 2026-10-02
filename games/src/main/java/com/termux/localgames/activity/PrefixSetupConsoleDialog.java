package com.termux.localgames.activity;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.termux.localgames.R;
import com.termux.localgames.data.FilePrefixSetupTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.PrefixSetupTask;
import com.termux.localgames.domain.RuntimeSetupTaskState;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.IOException;

import android.os.Handler;
import android.os.Looper;

import com.termux.localgames.api.LocalGames;

/** Modal TerminalView attached to the actual Termux GLIBC prefix setup session -- the
 *  PrefixSetupTask counterpart of {@link RuntimeSetupConsoleDialog}. Structurally identical
 *  (same golden-ratio sizing, same terminal attach/refresh/dismiss-on-complete flow, same host
 *  terminal lookup keyed purely by taskId); only the pre-terminal-attach status read differs,
 *  since GLIBC prefix tasks live in their own repository and domain type. */
final class PrefixSetupConsoleDialog extends Dialog {
    private final String taskId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TerminalSession consoleSession;
    private TerminalView terminalView;
    private TextView status;
    private boolean dismissed;
    private boolean setupFailed;

    private PrefixSetupConsoleDialog(@NonNull Context context, @NonNull String taskId) {
        super(context);
        this.taskId = taskId;
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    static PrefixSetupConsoleDialog show(@NonNull Context context, @NonNull String taskId) {
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
        content.setBackgroundColor(Color.BLACK);
        content.setContentDescription(
            getContext().getString(R.string.local_games_prefix_setup_console_title));

        status = new TextView(getContext());
        status.setTextColor(Color.rgb(170, 185, 210));
        status.setTextSize(13);
        status.setPadding(dp(8), dp(8), dp(8), 0);
        content.addView(status, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        terminalView = new TerminalView(getContext(), null);
        terminalView.setBackgroundColor(Color.BLACK);
        terminalView.setTerminalViewClient(new TermuxTerminalViewClientBase());
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getContext());
        terminalView.setTextSize(preferences == null ? 14 : preferences.getFontSize());
        content.addView(terminalView, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(content);
        terminalView.post(this::attachSetupSession);
        refreshStatus();

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    private static final double PHI = 1.6180339887;
    private static final double MAX_SHORT_FRACTION = 0.92;

    @Override
    protected void onStart() {
        super.onStart();
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
        window.setLayout(width, height);
    }

    @Override
    public void dismiss() {
        dismissed = true;
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
            handler.postDelayed(this::attachSetupSession, 100);
            return;
        }
        consoleSession = session;
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
            handler.postDelayed(this::dismiss, 2500);
        }
    }

    private void refreshStatus() {
        if (dismissed || !isShowing() || consoleSession != null) return;
        PrefixSetupTask task = readTask();
        String preparing = getContext().getString(R.string.local_games_runtime_setup_preparing);
        if (task == null) {
            status.setText(preparing);
        } else if (task.getState() == RuntimeSetupTaskState.FAILED) {
            setupFailed = true;
            status.setText(getContext().getString(R.string.local_games_runtime_setup_failed));
            return;
        } else if (task.getState() == RuntimeSetupTaskState.CANCELLED) {
            setupFailed = true;
            status.setText(RuntimeSetupTaskState.CANCELLED.name());
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
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
