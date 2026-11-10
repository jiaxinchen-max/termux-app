package com.termux.ui.wine.activity;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.ui.R;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.activity.RuntimeSetupConsoleDialog;
import com.termux.ui.activity.FixedBackupChooser;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.platform.ExternalBackupLocation;
import com.termux.ui.runtime.RuntimeBackupSharedStorage;
import com.termux.ui.wine.data.FileRootfsBackupTaskRepository;
import com.termux.ui.wine.domain.RootfsBackupTask;
import com.termux.ui.wine.domain.RootfsBackupTaskState;

import java.io.File;
import java.io.IOException;

/**
 * Drives backup and restore from the PRoot environment settings rows: an in-app
 * backup list from the fixed external directory, then a visible Termux session that reads or writes the selected
 * file directly. One instance lives for the process so a running job survives the host activity
 * being recreated; it owns every dialog of the flow, closes them when the host goes away and
 * shows its console and any unacknowledged result again on the new activity.
 */
public final class RuntimeBackupFlow {
    private static final RuntimeBackupFlow INSTANCE = new RuntimeBackupFlow();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rootfsPoll = this::pollRootfsTask;
    @Nullable private Activity host;
    @Nullable private RuntimeSetupConsoleDialog console;
    /** Finished while detached; shown on the next attach. */
    @Nullable private Runnable pendingReport;
    /** Dialogs currently on screen; all are closed when the host activity goes away. */
    @Nullable private AlertDialog chooser;
    @Nullable private AlertDialog resultDialog;
    /** The report behind {@link #resultDialog} until the user acknowledges it. */
    @Nullable private Runnable shownReport;
    /** The PRoot backup/restore task being watched until it is terminal, and its console title. */
    @Nullable private String rootfsTaskId;
    private int rootfsConsoleTitle;
    /** Supplied by the attached activity; called when a PRoot task ends so status rows refresh. */
    @Nullable private Runnable statusRefresh;

    public static RuntimeBackupFlow instance() { return INSTANCE; }

    private RuntimeBackupFlow() { }

    public void attach(Activity activity, @Nullable Runnable onStatusChanged) {
        host = activity;
        statusRefresh = onStatusChanged;
        Runnable report = pendingReport;
        if (report != null) {
            pendingReport = null;
            report.run();
        }
        if (rootfsTaskId != null) {
            showRootfsConsole(rootfsTaskId, rootfsConsoleTitle);
            handler.removeCallbacks(rootfsPoll);
            handler.postDelayed(rootfsPoll, 300);
        }
    }

    public void detach(Activity activity) {
        if (host != activity) return;
        handler.removeCallbacks(rootfsPoll);
        if (console != null) console.dismiss();
        console = null;
        if (chooser != null) chooser.dismiss();
        chooser = null;
        // A result the user has not acknowledged (OK or cancel) is shown again on the next activity.
        if (shownReport != null) pendingReport = shownReport;
        shownReport = null;
        if (resultDialog != null) resultDialog.dismiss();
        resultDialog = null;
        host = null;
        statusRefresh = null;
    }

    public void createArchive(String defaultName, java.util.function.Consumer<File> onChosen) {
        Activity activity = host;
        if (activity == null) return;
        try { onChosen.accept(ExternalBackupLocation.newArchive(activity, defaultName)); }
        catch (IOException error) { showFailure(error); }
    }

    public void pickArchive(String extension, java.util.function.Consumer<File> onChosen) {
        Activity activity = host;
        if (activity == null) return;
        if (chooser != null) chooser.dismiss();
        try { chooser = FixedBackupChooser.show(activity, extension, onChosen); }
        catch (IOException error) { showFailure(error); }
    }

    /** A failure raised outside a running job, for example a refused free-space preflight. */
    public void showFailure(Throwable error) {
        Activity activity = host;
        if (activity == null) { pendingReport = () -> showFailure(error); return; }
        showMessage(R.string.local_games_backup_title, describe(activity, error));
    }

    /**
     * Shows the live console of a PRoot backup/restore task and watches the task until it is
     * terminal, then reports its outcome. The console is tracked here so detach() closes it with
     * the other dialogs, and attach() shows it again on the recreated activity while the task runs.
     */
    public void watchRootfsTask(String taskId, int consoleTitleRes) {
        rootfsTaskId = taskId;
        rootfsConsoleTitle = consoleTitleRes;
        showRootfsConsole(taskId, consoleTitleRes);
        handler.removeCallbacks(rootfsPoll);
        handler.postDelayed(rootfsPoll, 500);
    }

    private void showRootfsConsole(String taskId, int titleRes) {
        Activity activity = host;
        if (activity == null) return;
        if (console != null) console.dismiss();
        RuntimeSetupConsoleDialog.TaskStatusLookup lookup = id -> {
            try {
                RootfsBackupTask task = new FileRootfsBackupTaskRepository(new GameStoragePaths(
                    activity.getFilesDir()).getBackupTasksDirectory()).find(id).orElse(null);
                if (task == null) return null;
                return new RuntimeSetupConsoleDialog.TaskStatusLookup.Status(
                    task.getState() == RootfsBackupTaskState.FAILED, false,
                    task.getState() == RootfsBackupTaskState.SUCCEEDED, task.getState().name());
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        };
        console = RuntimeSetupConsoleDialog.show(activity, taskId, titleRes, lookup);
    }

    private void pollRootfsTask() {
        Activity activity = host;
        String taskId = rootfsTaskId;
        if (activity == null || taskId == null) return;
        RootfsBackupTask task = null;
        try {
            task = new FileRootfsBackupTaskRepository(
                new GameStoragePaths(activity.getFilesDir()).getBackupTasksDirectory())
                .find(taskId).orElse(null);
        } catch (IOException | RuntimeException ignored) {
            // The task file may not be written yet or be mid-replacement; try again.
        }
        if (task == null || !task.getState().isTerminal()) {
            handler.postDelayed(rootfsPoll, 500);
            return;
        }
        rootfsTaskId = null;
        if (statusRefresh != null) statusRefresh.run();
        showRootfsResult(task);
    }

    private void showRootfsResult(RootfsBackupTask task) {
        Activity activity = host;
        if (activity == null) { pendingReport = () -> showRootfsResult(task); return; }
        String archive = task.getArchivePath().isEmpty()
            ? ExternalBackupLocation.directory().getPath()
            : task.getArchivePath();
        String message;
        if (task.getState() == RootfsBackupTaskState.SUCCEEDED) {
            message = activity.getString(task.getKind() == RootfsBackupTask.Kind.BACKUP
                ? R.string.local_games_backup_create_done : R.string.local_games_backup_restore_done, archive);
        } else {
            String code = task.getErrorCode().isEmpty() ? "backup_task_failed" : task.getErrorCode();
            message = activity.getString(R.string.local_games_backup_failed, code);
        }
        showMessage(R.string.local_games_backup_title, message);
    }

    /**
     * Shows a result dialog. The report stays queued in {@link #shownReport} until the user
     * acknowledges it with OK or by cancelling, so a dismissal caused by the host going away
     * (see detach) re-queues it for the next activity.
     */
    private void showMessage(int titleRes, String message) {
        Activity activity = host;
        if (activity == null) { pendingReport = () -> showMessage(titleRes, message); return; }
        shownReport = () -> showMessage(titleRes, message);
        resultDialog = new FrostedDialogBuilder(activity)
            .setTitle(titleRes)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> shownReport = null)
            .setOnCancelListener(dialog -> shownReport = null)
            .setOnDismissListener(dialog -> resultDialog = null)
            .show();
    }

    private static String describe(Activity activity, Throwable error) {
        if (error instanceof RuntimeBackupSharedStorage.InsufficientSpaceException) {
            RuntimeBackupSharedStorage.InsufficientSpaceException space =
                (RuntimeBackupSharedStorage.InsufficientSpaceException) error;
            return activity.getString(R.string.local_games_backup_insufficient_space,
                Formatter.formatFileSize(activity, space.getRequiredBytes()),
                Formatter.formatFileSize(activity, space.getAvailableBytes()));
        }
        if ("runtime_backup_destination_not_shared_storage".equals(error.getMessage())) {
            return activity.getString(R.string.local_games_backup_destination_not_shared);
        }
        String code = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return activity.getString(R.string.local_games_backup_failed, code);
    }
}
