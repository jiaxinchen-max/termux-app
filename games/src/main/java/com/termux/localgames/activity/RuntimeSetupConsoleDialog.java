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
import androidx.annotation.Nullable;

import com.termux.localgames.R;
import com.termux.localgames.data.FileRuntimeSetupTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.RuntimeSetupTask;
import com.termux.localgames.domain.RuntimeSetupTaskState;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import java.io.IOException;

import android.os.Handler;
import android.os.Looper;

import com.termux.localgames.api.LocalGames;

/** Modal TerminalView attached to the actual Termux setup session. Nothing but the
 *  log/terminal content itself is shown -- no title, no button chrome -- tapping outside the
 *  dialog (losing focus) dismisses it, same as any other modal. */
final class RuntimeSetupConsoleDialog extends Dialog {
    /** Pre-session-attach status lookup, abstracted so this dialog can show progress for any
     *  taskId-addressed task family, not just RuntimeSetupTask -- e.g. the base-environment
     *  Backup/Restore feature's own RootfsBackupTask, which has no recipe/component/container
     *  fields and a smaller state machine (no CANCELLED). Once the real TerminalSession attaches
     *  (attachSetupSession()), this is no longer consulted -- the live terminal output itself
     *  takes over as the source of truth. */
    interface TaskStatusLookup {
        /** Null means "no task record yet" (still preparing, nothing written). */
        @Nullable Status lookup(String taskId);

        final class Status {
            final boolean failed;
            final boolean cancelled;
            final String stateName;

            Status(boolean failed, boolean cancelled, @NonNull String stateName) {
                this.failed = failed;
                this.cancelled = cancelled;
                this.stateName = stateName;
            }
        }
    }

    private static TaskStatusLookup defaultLookup(Context context) {
        return taskId -> {
            try {
                RuntimeSetupTask task = new FileRuntimeSetupTaskRepository(new GameStoragePaths(
                    context.getFilesDir()).getRuntimeSetupTasksDirectory()).find(taskId)
                    .orElse(null);
                if (task == null) return null;
                return new TaskStatusLookup.Status(
                    task.getState() == RuntimeSetupTaskState.FAILED,
                    task.getState() == RuntimeSetupTaskState.CANCELLED,
                    task.getState().name());
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        };
    }

    private final String taskId;
    private final TaskStatusLookup statusLookup;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TerminalSession consoleSession;
    private TerminalView terminalView;
    private TextView status;
    private boolean dismissed;
    private boolean setupFailed;
    private int titleRes = R.string.local_games_runtime_setup_console_title;

    private RuntimeSetupConsoleDialog(@NonNull Context context, @NonNull String taskId,
                                      @NonNull TaskStatusLookup statusLookup) {
        super(context);
        this.taskId = taskId;
        this.statusLookup = statusLookup;
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    static RuntimeSetupConsoleDialog show(@NonNull Context context, @NonNull String taskId) {
        return show(context, taskId, R.string.local_games_runtime_setup_console_title);
    }

    static RuntimeSetupConsoleDialog show(@NonNull Context context, @NonNull String taskId,
                                              int titleRes) {
        return show(context, taskId, titleRes, defaultLookup(context));
    }

    static RuntimeSetupConsoleDialog show(@NonNull Context context, @NonNull String taskId,
                                              int titleRes, @NonNull TaskStatusLookup statusLookup) {
        RuntimeSetupConsoleDialog dialog = new RuntimeSetupConsoleDialog(context, taskId,
            statusLookup);
        dialog.titleRes = titleRes;
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
        // No visible title text, but keep the label available to accessibility services.
        content.setContentDescription(getContext().getString(titleRes));

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

    // Golden ratio (1:1.618): the dialog's own box always stays golden, and its long side
    // tracks whichever screen axis is actually longer -- wide/"flat" in landscape, tall/"thin"
    // in portrait -- so it matches the screen's own orientation instead of always being a tall
    // rectangle regardless of how the screen is held. The long side targets the golden fraction
    // (1/phi =~ 0.618) of the screen's matching long-axis dimension, a substantial majority of
    // the available space without spanning edge to edge.
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
            // The screen's short axis is too narrow for a golden box this large (an unusually
            // elongated screen) -- fit to that instead and shrink the long side to match, so
            // the box itself still stays exactly golden.
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
        // TerminalView initializes its renderer from the configured text size.  Do not attach a
        // session until that initialization is observable on the UI queue.
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
            // Give the user a moment to actually read the final log lines (SUCCEEDED/FAILED)
            // before this closes itself -- a completed Termux session is normally dismissed by
            // pressing Enter, but this dialog owns no interactive terminal chrome.
            handler.postDelayed(this::dismiss, 2500);
        }
    }

    // The session only appears after a (potentially multi-minute) one-time X11 bridge install,
    // so without this the dialog looks identical to a hang.
    private void refreshStatus() {
        if (dismissed || !isShowing() || consoleSession != null) return;
        TaskStatusLookup.Status taskStatus = statusLookup.lookup(taskId);
        String preparing = getContext().getString(R.string.local_games_runtime_setup_preparing);
        if (taskStatus == null) {
            status.setText(preparing);
        } else if (taskStatus.failed) {
            setupFailed = true;
            status.setText(getContext().getString(R.string.local_games_runtime_setup_failed));
            return;
        } else if (taskStatus.cancelled) {
            setupFailed = true;
            status.setText(taskStatus.stateName);
            return;
        } else {
            status.setText(preparing + " (" + taskStatus.stateName + ")");
        }
        handler.postDelayed(this::refreshStatus, 500);
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
