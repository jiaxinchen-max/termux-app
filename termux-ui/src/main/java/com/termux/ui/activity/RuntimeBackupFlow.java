package com.termux.ui.activity;

import android.app.Activity;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.termux.ui.R;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.RuntimeSetupRequest;
import com.termux.ui.data.FileRootfsBackupTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.RootfsBackupTask;
import com.termux.ui.domain.RootfsBackupTaskState;
import com.termux.ui.runtime.RuntimeBackupManager;
import com.termux.ui.runtime.RuntimeBackupSharedStorage;
import com.termux.ui.runtime.RuntimeBackupType;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Drives backup and restore from the Native and Proot environment settings rows: an in-app
 * chooser rooted at shared storage, then a visible Termux session that reads or writes the chosen
 * file directly. One instance lives for the process so a running job survives the host activity
 * being recreated; it owns every dialog of the flow, closes them when the host goes away and
 * shows its console and any unacknowledged result again on the new activity.
 */
final class RuntimeBackupFlow {
    private static final RuntimeBackupFlow INSTANCE = new RuntimeBackupFlow();

    private interface JobSource {
        RuntimeBackupManager.Job prepare(RuntimeBackupManager manager) throws IOException;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesRuntimeBackup"));
    private final Runnable poll = this::pollJob;
    private final Runnable rootfsPoll = this::pollRootfsTask;
    @Nullable private Activity host;
    @Nullable private RuntimeBackupManager manager;
    @Nullable private RuntimeBackupManager.Job activeJob;
    @Nullable private RuntimeSetupConsoleDialog console;
    /** Prepared while no activity was attached; started on the next attach. */
    @Nullable private RuntimeBackupManager.Job unstartedJob;
    /** Finished while no activity was attached; shown on the next attach. */
    @Nullable private Runnable pendingReport;
    /** Finished while detached, so its job directory could not be cleaned yet. */
    @Nullable private RuntimeBackupManager.Job uncleanedJob;
    /** Dialogs currently on screen; all are closed when the host activity goes away. */
    @Nullable private SharedStorageChooserDialog chooser;
    @Nullable private AlertDialog restoreConfirmation;
    @Nullable private AlertDialog resultDialog;
    /** The report behind {@link #resultDialog} until the user acknowledges it. */
    @Nullable private Runnable shownReport;
    /** The PRoot backup/restore task being watched until it is terminal, and its console title. */
    @Nullable private String rootfsTaskId;
    private int rootfsConsoleTitle;
    /** Supplied by the attached activity; called when a PRoot task ends so status rows refresh. */
    @Nullable private Runnable statusRefresh;
    private boolean preparing;

    static RuntimeBackupFlow instance() { return INSTANCE; }

    private RuntimeBackupFlow() { }

    void attach(Activity activity, @Nullable Runnable onStatusChanged) {
        host = activity;
        statusRefresh = onStatusChanged;
        manager = new RuntimeBackupManager(activity, activity.getFilesDir());
        if (uncleanedJob != null) {
            manager.cleanup(uncleanedJob);
            uncleanedJob = null;
        }
        Runnable report = pendingReport;
        if (report != null) {
            pendingReport = null;
            report.run();
        }
        RuntimeBackupManager.Job job = unstartedJob;
        if (job != null) {
            unstartedJob = null;
            startJob(job);
        } else if (activeJob != null) {
            showConsole();
            handler.removeCallbacks(poll);
            handler.postDelayed(poll, 300);
        }
        if (rootfsTaskId != null) {
            showRootfsConsole(rootfsTaskId, rootfsConsoleTitle);
            handler.removeCallbacks(rootfsPoll);
            handler.postDelayed(rootfsPoll, 300);
        }
    }

    void detach(Activity activity) {
        if (host != activity) return;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(rootfsPoll);
        if (console != null) console.dismiss();
        console = null;
        if (chooser != null) chooser.dismiss();
        chooser = null;
        if (restoreConfirmation != null) restoreConfirmation.dismiss();
        restoreConfirmation = null;
        // A result the user has not acknowledged (OK or cancel) is shown again on the next activity.
        if (shownReport != null) pendingReport = shownReport;
        shownReport = null;
        if (resultDialog != null) resultDialog.dismiss();
        resultDialog = null;
        host = null;
        manager = null;
        statusRefresh = null;
    }

    boolean isRunning() { return activeJob != null || preparing || unstartedJob != null; }

    /** Native "Backup to file": choose a folder and name inside shared storage, export straight there. */
    void startExport() {
        if (host == null) return;
        if (isRunning()) { showConsole(); return; }
        pickSaveFile(RuntimeBackupSharedStorage.ARCHIVE_EXTENSION,
            RuntimeBackupSharedStorage.exportFileName(RuntimeBackupType.GLIBC, new Date()),
            file -> begin(m -> m.prepareExport(RuntimeBackupType.GLIBC, file)));
    }

    /** Native "Restore from file": choose a .tar inside shared storage, confirm, restore it in place. */
    void startRestore() {
        Activity activity = host;
        if (activity == null) return;
        if (isRunning()) { showConsole(); return; }
        pickArchive(RuntimeBackupSharedStorage.ARCHIVE_EXTENSION, file -> {
            restoreConfirmation = new FrostedDialogBuilder(activity)
                .setTitle(R.string.local_games_runtime_backup_restore_title)
                .setMessage(R.string.local_games_runtime_backup_restore_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_games_runtime_backup_restore, (dialog, which) ->
                    begin(m -> m.prepareRestoreInPlace(file)))
                .setOnDismissListener(dialog -> restoreConfirmation = null)
                .show();
        });
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

    private void begin(JobSource source) {
        RuntimeBackupManager current = manager;
        if (current == null || isRunning()) return;
        preparing = true;
        Activity activity = host;
        if (activity != null) {
            Toast.makeText(activity, R.string.local_games_runtime_backup_working, Toast.LENGTH_SHORT).show();
        }
        executor.execute(() -> {
            try {
                RuntimeBackupManager.Job job = source.prepare(current);
                handler.post(() -> {
                    preparing = false;
                    if (host == null) unstartedJob = job; else startJob(job);
                });
            } catch (IOException | RuntimeException error) {
                handler.post(() -> {
                    preparing = false;
                    finish(null, error);
                });
            }
        });
    }

    private void startJob(RuntimeBackupManager.Job job) {
        Activity activity = host;
        if (activity == null) { unstartedJob = job; return; }
        activeJob = job;
        showConsole();
        try {
            LocalGames.requireHost(activity).startRuntimeSetup(new RuntimeSetupRequest(
                job.getTaskId(), job.getScript().getCanonicalPath(), job.getSpec().getCanonicalPath(),
                job.getDirectory().getCanonicalPath()));
            handler.postDelayed(poll, 300);
        } catch (IOException | LaunchHostException | RuntimeException error) {
            finish(job, error);
        }
    }

    private void showConsole() {
        Activity activity = host;
        RuntimeBackupManager.Job job = activeJob;
        if (activity == null || job == null) return;
        if (console != null) console.dismiss();
        console = RuntimeSetupConsoleDialog.show(activity, job.getTaskId(),
            R.string.local_games_runtime_backup_console_title);
    }

    private void pollJob() {
        RuntimeBackupManager current = manager;
        RuntimeBackupManager.Job job = activeJob;
        if (current == null || job == null) return;
        try {
            RuntimeBackupManager.Outcome outcome = current.readOutcome(job);
            if (outcome == null) {
                handler.postDelayed(poll, 500);
            } else if (!outcome.isSuccess()) {
                finish(job, new IOException(outcome.getError()));
            } else {
                finish(job, null);
            }
        } catch (IOException | RuntimeException error) {
            finish(job, error);
        }
    }

    /** Ends the current job (or a failed preparation, {@code job == null}) with a result dialog. */
    private void finish(@Nullable RuntimeBackupManager.Job job, @Nullable Throwable error) {
        handler.removeCallbacks(poll);
        activeJob = null;
        if (console != null) console.dismiss();
        console = null;
        if (manager != null) manager.cleanup(job); else uncleanedJob = job;
        Runnable report = () -> report(job, error);
        if (host == null) pendingReport = report; else report.run();
    }

    private void report(@Nullable RuntimeBackupManager.Job job, @Nullable Throwable error) {
        Activity activity = host;
        if (activity == null) { pendingReport = () -> report(job, error); return; }
        String message;
        if (error != null) {
            message = describe(activity, error);
        } else if (job != null && job.isExport()) {
            message = activity.getString(R.string.local_games_runtime_backup_exported_to,
                job.getArchive().getAbsolutePath());
        } else {
            message = activity.getString(R.string.local_games_runtime_backup_restored,
                activity.getString(R.string.local_games_runtime_backup_glibc));
        }
        showMessage(R.string.local_games_runtime_backup_title, message);
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
            ? new GameStoragePaths(activity.getFilesDir()).getExternalBackupFile().getPath()
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
            return activity.getString(R.string.local_games_runtime_backup_insufficient_space,
                Formatter.formatFileSize(activity, space.getRequiredBytes()),
                Formatter.formatFileSize(activity, space.getAvailableBytes()));
        }
        if ("runtime_backup_destination_not_shared_storage".equals(error.getMessage())) {
            return activity.getString(R.string.local_games_runtime_backup_destination_not_shared);
        }
        String code = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return activity.getString(R.string.local_games_runtime_backup_failed, code);
    }
}
