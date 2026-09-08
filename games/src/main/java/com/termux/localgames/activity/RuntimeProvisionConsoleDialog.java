package com.termux.localgames.activity;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.google.android.material.button.MaterialButton;
import com.termux.localgames.R;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import android.os.Handler;
import android.os.Looper;

import com.termux.localgames.api.LocalGames;

/** Modal TerminalView attached to the actual Termux provisioning session. */
final class RuntimeProvisionConsoleDialog extends Dialog {
    private final String taskId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TerminalSession consoleSession;
    private TerminalView terminalView;
    private boolean dismissed;
    private int titleRes = R.string.local_games_runtime_provision_console_title;

    private RuntimeProvisionConsoleDialog(@NonNull Context context, @NonNull String taskId) {
        super(context);
        this.taskId = taskId;
        setCancelable(false);
        setCanceledOnTouchOutside(false);
    }

    static void show(@NonNull Context context, @NonNull String taskId) {
        show(context, taskId, R.string.local_games_runtime_provision_console_title);
    }

    static void show(@NonNull Context context, @NonNull String taskId, int titleRes) {
        RuntimeProvisionConsoleDialog dialog = new RuntimeProvisionConsoleDialog(context, taskId);
        dialog.titleRes = titleRes;
        dialog.show();
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
        title.setText(titleRes);
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        content.addView(title, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        terminalView = new TerminalView(getContext(), null);
        terminalView.setBackgroundColor(Color.BLACK);
        terminalView.setTerminalViewClient(new TermuxTerminalViewClientBase());
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getContext());
        terminalView.setTextSize(preferences == null ? 14 : preferences.getFontSize());
        LinearLayout.LayoutParams terminalParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        terminalParams.topMargin = dp(12);
        content.addView(terminalView, terminalParams);

        MaterialButton close = new MaterialButton(getContext());
        close.setText(R.string.local_games_component_console_hide);
        close.setOnClickListener(view -> dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.gravity = Gravity.END;
        closeParams.topMargin = dp(8);
        content.addView(close, closeParams);

        setContentView(content);
        terminalView.post(this::attachProvisionSession);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) {
            window.setLayout((int) (getContext().getResources().getDisplayMetrics().widthPixels * .94f),
                (int) (getContext().getResources().getDisplayMetrics().heightPixels * .78f));
        }
    }

    @Override
    public void dismiss() {
        dismissed = true;
        handler.removeCallbacksAndMessages(null);
        super.dismiss();
    }

    private void attachProvisionSession() {
        if (dismissed || !isShowing()) return;
        // TerminalView initializes its renderer from the configured text size.  Do not attach a
        // session until that initialization is observable on the UI queue.
        if (terminalView == null || terminalView.mRenderer == null) {
            handler.postDelayed(this::attachProvisionSession, 50);
            return;
        }
        TerminalSession session = LocalGames.requireHost(getContext())
            .getRuntimeProvisionTerminal(taskId);
        if (session == null) {
            handler.postDelayed(this::attachProvisionSession, 100);
            return;
        }
        consoleSession = session;
        terminalView.attachSession(session);
        refreshTerminal();
    }

    private void refreshTerminal() {
        if (dismissed || terminalView == null || consoleSession == null) return;
        terminalView.onScreenUpdated();
        if (consoleSession.isRunning()) {
            handler.postDelayed(this::refreshTerminal, 100);
        } else {
            // A completed Termux session is normally removed by pressing Enter. The Games
            // dialog owns no interactive terminal chrome, so close it after the final frame.
            handler.postDelayed(this::dismiss, 180);
        }
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
