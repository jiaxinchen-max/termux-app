package com.termux.ui.activity;

import android.app.Activity;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.ui.platform.ExternalBackupLocation;
import com.termux.ui.R;
import com.termux.ui.data.FileRootfsBackupTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.RootfsBackupTask;
import com.termux.ui.domain.RootfsBackupTaskState;
import com.termux.ui.runtime.RuntimeBackupSharedStorage;

import java.io.File;
import java.io.IOException;

/**
 * Drives backup and restore from the PRoot environment settings rows: an in-app
 * chooser rooted at shared storage, then a visible Termux session that reads or writes the chosen
 * file directly. One instance lives for the process so a running job survives the host activity
 * being recreated; it owns every dialog of the flow, closes them when the host goes away and
 * shows its console and any unacknowledged result again on the new activity.
 */
final class RuntimeBackupFlow {
    private static final RuntimeBackupFlow INSTANCE = new RuntimeBackupFlow();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rootfsPoll = this::pollRootfsTask;
    @Nullable private Activity host;
    @Nullable private RuntimeSetupConsoleDialog console;
    /** Finished while detached; shown on the next attach. */
    @Nullable private Runnable pendingReport;
    /** Dialogs currently on screen; all are closed when the host activity goes away. */
    @Nullable private SharedStorageChooserDialog chooser;
    @Nullable private AlertDialog resultDialog;
    /** The report behind {@link #resultDialog} until the user acknowledges it. */
    @Nullable private Runnable shownReport;
    /** The PRoot backup/restore task being watched until it is terminal, and its console title. */
    @Nullable private String rootfsTaskId;
    private int rootfsConsoleTitle;
    /** Supplied by the attached activity; called when a PRoot task ends so status rows refresh. */
    @Nullable private Runnable statusRefresh;

    static RuntimeBackupFlow instance() { return INSTANCE; }

    private RuntimeBackupFlow() { }

    void attach(Activity activity, @Nullable Runnable onStatusChanged) {
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

    void detach(Activity activity) {
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

    /**
     * Opens the in-app save chooser (shared storage only) for any kind of backup. The chooser is
     * tracked here so it is closed together with the other dialogs when the host activity goes.
     */
    void pickSaveFile(String extension, String defaultName, SharedStorageChooserDialog.OnChosen onChosen) {
        Activity activity = host;
        if (activity == null) return;
        if (chooser != null) chooser.dismiss();
        chooser = SharedStorageChooserDialog.chooseSaveFile(activity,
            Environment.getExternalStorageDirectory(), defaultName, extension, file -> {
                chooser = null;
                onChosen.onChosen(file);
            });
    }

    /** Opens the in-app chooser listing only files ending in {@code extension}; see pickSaveFile. */
    void pickArchive(String extension, SharedStorageChooserDialog.OnChosen onChosen) {
        Activity activity = host;
        if (activity == null) return;
        if (chooser != null) chooser.dismiss();
        chooser = SharedStorageChooserDialog.chooseArchive(activity,
            Environment.getExternalStorageDirectory(), extension, file -> {
                chooser = null;
                onChosen.onChosen(file);
            });
    }

    /** A failure raised outside a running job, for example a refused free-space preflight. */
    void showFailure(Throwable error) {
        Activity activity = host;
        if (activity == null) { pendingReport = () -> showFailure(error); return; }
        showMessage(R.string.local_games_backup_title, describe(activity, error));
    }

    /**
     * Shows the live console of a PRoot backup/restore task and watches the task until it is
     * terminal, then reports its outcome. The console is tracked here so detach() closes it with
     * the other dialogs, and attach() shows it again on the recreated activity while the task runs.
     */
    void watchRootfsTask(String taskId, int consoleTitleRes) {
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
            ? ExternalBackupLocation.file().getPath()
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
