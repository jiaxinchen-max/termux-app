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
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.domain.RuntimeSetupTask;
import com.termux.ui.wine.domain.RuntimeSetupTaskState;
import com.termux.view.TerminalView;

import java.io.IOException;

/** Modal TerminalView attached to the actual Termux setup session. Nothing but the
 *  preparation log and terminal content are shown -- tapping outside the
 *  dialog (losing focus) dismisses it, same as any other modal. */
public final class RuntimeSetupConsoleDialog extends Dialog {
    /** Pre-session-attach status lookup, abstracted so this dialog can show progress for any
     *  taskId-addressed task family, not just RuntimeSetupTask -- e.g. the base-environment
     *  Backup/Restore feature's own RootfsBackupTask, which has no recipe/component/container
     *  fields and a smaller state machine (no CANCELLED). Once the real TerminalSession attaches
     *  (attachSetupSession()), this is no longer consulted -- the live terminal output itself
     *  takes over as the source of truth. */
    public interface TaskStatusLookup {
        /** Null means "no task record yet" (still preparing, nothing written). */
        @Nullable Status lookup(String taskId);

        final class Status {
            final boolean failed;
            final boolean cancelled;
            final boolean succeeded;
            final String stateName;

            /** Status names used by setup, component and archive task families. */
            public Status(boolean failed, boolean cancelled, @NonNull String stateName) {
                this(failed, cancelled, "SUCCEEDED".equals(stateName) || "INSTALLED".equals(stateName), stateName);
            }

            public Status(boolean failed, boolean cancelled, boolean succeeded,
                   @NonNull String stateName) {
                this.failed = failed;
                this.cancelled = cancelled;
                this.succeeded = succeeded;
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
                    task.getState() == RuntimeSetupTaskState.SUCCEEDED,
                    task.getState() == RuntimeSetupTaskState.FAILED
                        ? task.getErrorCode() : task.getState().name());
            } catch (IOException | RuntimeException error) {
                return new TaskStatusLookup.Status(true, false, false, "task_read_failed: " + error.getMessage());
            }
        };
    }

    private final String taskId;
    private final boolean componentTask;
    private final TaskStatusLookup statusLookup;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TerminalSession consoleSession;
    private TerminalView terminalView;
    private TextView status;
    private SetupPreparationConsole preparation;
    private boolean dismissed;
    private boolean setupFailed;
    private int successMessage = R.string.local_games_preparation_completed;
    private int failureMessage = R.string.local_games_runtime_setup_failed;
    private int titleRes = R.string.local_games_runtime_setup_console_title;
    public RuntimeSetupConsoleDialog setOutcomeMessages(int successMessage, int failureMessage) {
        this.successMessage = successMessage;
        this.failureMessage = failureMessage;
        return this;
    }

    private RuntimeSetupConsoleDialog(@NonNull Context context, @NonNull String taskId,
                                      @NonNull TaskStatusLookup statusLookup, boolean componentTask) {
        super(context);
        this.taskId = taskId;
        this.componentTask = componentTask;
        this.statusLookup = statusLookup;
        setCancelable(true);
        setCanceledOnTouchOutside(true);
    }

    public static RuntimeSetupConsoleDialog show(@NonNull Context context, @NonNull String taskId) {
        return show(context, taskId, R.string.local_games_runtime_setup_console_title);
    }

    public static RuntimeSetupConsoleDialog show(@NonNull Context context, @NonNull String taskId,
                                              int titleRes) {
        return show(context, taskId, titleRes, defaultLookup(context));
    }

    public static RuntimeSetupConsoleDialog show(@NonNull Context context, @NonNull String taskId,
                                              int titleRes, @NonNull TaskStatusLookup statusLookup) {
        RuntimeSetupConsoleDialog dialog = new RuntimeSetupConsoleDialog(context, taskId,
            statusLookup, false);
        dialog.titleRes = titleRes;
        dialog.show();
        return dialog;
    }

    public static RuntimeSetupConsoleDialog showComponent(@NonNull Context context, @NonNull String taskId,
                                                   int titleRes) {
        TaskStatusLookup lookup = id -> {
            try {
                ComponentTask task = new FileComponentTaskRepository(
                    new ComponentStoragePaths(context.getFilesDir())
                        .getTasksDirectory()).find(id).orElse(null);
                if (task == null) return null;
                ComponentTaskState state = task.getState();
                return new TaskStatusLookup.Status(state == ComponentTaskState.FAILED,
                    state == ComponentTaskState.CANCELLED ||
                        state == ComponentTaskState.PAUSED,
                    state == ComponentTaskState.INSTALLED, state.name());
            } catch (IOException error) {
                return null;
            }
        };
        RuntimeSetupConsoleDialog dialog = new RuntimeSetupConsoleDialog(context, taskId, lookup, true);
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
        FrostedWindow.clipToPanel(content, getContext());
        // No visible title text, but keep the label available to accessibility services.
        content.setContentDescription(getContext().getString(titleRes));

        status = new TextView(getContext());
        status.setTextColor(ContextCompat.getColor(getContext(),
            R.color.local_games_terminal_secondary));
        status.setTextSize(13);
        status.setText(R.string.local_games_runtime_setup_preparing);
        status.setPadding(dp(8), dp(8), dp(8), 0);
        content.addView(status, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        preparation = new SetupPreparationConsole(getContext(), taskId, componentTask, message -> {
            showFailure(message);
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
            // The screen's short axis is too narrow for a golden box this large (an unusually
            // elongated screen) -- fit to that instead and shrink the long side to match, so
            // the box itself still stays exactly golden.
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
        // TerminalView initializes its renderer from the configured text size.  Do not attach a
        // session until that initialization is observable on the UI queue.
        if (terminalView == null || terminalView.mRenderer == null) {
            handler.postDelayed(this::attachSetupSession, 50);
            return;
        }
        TerminalSession session = LocalGames.requireHost(getContext())
            .getRuntimeSetupTerminal(taskId);
        if (session == null) {
            // A successful no-op setup may finish without publishing a terminal. Keep failures
            // open so the preparation output and error remain readable.
            TaskStatusLookup.Status taskStatus = statusLookup.lookup(taskId);
            if (taskStatus != null
                && taskStatus.succeeded) {
                complete();
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
            TaskStatusLookup.Status taskStatus = statusLookup.lookup(taskId);
            if (setupFailed) return;
            // The process exit can arrive before the foreground owner persists its final event.
            // Do not freeze a transient BUILDING state as the failure reason.
            if (taskStatus != null && !taskStatus.failed && !taskStatus.cancelled && !taskStatus.succeeded) {
                handler.postDelayed(this::refreshTerminal, 500);
                return;
            }
            if (consoleSession.getExitStatus() != 0 || (taskStatus != null && (taskStatus.failed || taskStatus.cancelled))) {
                showFailure(taskStatus == null ? "setup_process_failed" : taskStatus.stateName);
            } else if (taskStatus != null && !taskStatus.succeeded) {
                handler.postDelayed(this::refreshTerminal, 500);
            } else {
                handler.postDelayed(this::complete, 1500);
            }
        }
    }

    // The session only appears after a (potentially multi-minute) one-time X11 bridge install,
    // so without this the dialog looks identical to a hang.
    private void refreshStatus() {
        if (dismissed || !isShowing() || consoleSession != null || setupFailed) return;
        TaskStatusLookup.Status taskStatus = statusLookup.lookup(taskId);
        String preparing = getContext().getString(R.string.local_games_runtime_setup_preparing);
        if (taskStatus == null) {
            status.setText(preparing);
        } else if (taskStatus.failed) {
            showFailure(taskStatus.stateName);
            return;
        } else if (taskStatus.cancelled) {
            setupFailed = true;
            status.setText(taskStatus.stateName);
            return;
        } else if (taskStatus.succeeded) {
            // The task finished before its terminal session ever became observable -- a fast,
            // effectively no-op setup. attachSetupSession() would otherwise keep polling
            // getRuntimeSetupTerminal() forever for a session that no longer exists, leaving this
            // dialog stuck on a blank black terminal. Nothing is left to show, so close --
            // mirroring refreshTerminal()'s dismiss-on-finish for the normal (slow) path.
            complete();
            return;
        } else {
            status.setText(preparing + " (" + taskStatus.stateName + ")");
        }
        handler.postDelayed(this::refreshStatus, 500);
    }

    private void complete() {
        if (dismissed || setupFailed) return;
        android.widget.Toast.makeText(getContext(), successMessage,
            android.widget.Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private void showFailure(String message) {
        setupFailed = true;
        applyTheme();
        status.setVisibility(android.view.View.VISIBLE);
        int recoveryMessage = 0;
        if ("rootfs_rebuild_required".equals(message)) {
            recoveryMessage = R.string.local_games_rootfs_rebuild_required;
        } else if ("rootfs_prefix_recovery_required".equals(message)) {
            recoveryMessage = R.string.local_games_rootfs_prefix_recovery_required;
        } else if ("rootfs_insufficient_storage".equals(message)) {
            recoveryMessage = R.string.local_games_rootfs_insufficient_storage;
        } else if ("rootfs_prefix_insufficient_storage".equals(message)) {
            recoveryMessage = R.string.local_games_rootfs_prefix_insufficient_storage;
        }
        status.setText(recoveryMessage != 0 ? getContext().getString(recoveryMessage)
            : getContext().getString(failureMessage) + "\n" + message);
    }

    private int dp(int value) {
        return Math.round(value * getContext().getResources().getDisplayMetrics().density);
    }
}
