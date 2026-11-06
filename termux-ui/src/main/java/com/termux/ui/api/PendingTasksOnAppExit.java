package com.termux.ui.api;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.FileCustomComponentInstallTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;
import com.termux.ui.domain.CustomComponentInstallTask;
import com.termux.ui.domain.CustomComponentInstallTaskState;
import com.termux.ui.wine.data.FileLaunchTaskRepository;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.data.FileResetTaskRepository;
import com.termux.ui.wine.data.FileRootfsBackupTaskRepository;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.domain.LaunchTask;
import com.termux.ui.wine.domain.LaunchTaskState;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.domain.ResetTask;
import com.termux.ui.wine.domain.ResetTaskState;
import com.termux.ui.wine.domain.RootfsBackupTask;
import com.termux.ui.wine.domain.RootfsBackupTaskState;
import com.termux.ui.wine.domain.RuntimeSetupTask;
import com.termux.ui.wine.domain.RuntimeSetupTaskState;

import java.io.File;
import java.io.IOException;

/** Persists an explicit app exit without starting services or resubmitting work. */
public final class PendingTasksOnAppExit {
    private static final String APP_EXIT = "app_exit";

    private PendingTasksOnAppExit() {}

    /** Call only after task owners and native workers have stopped, to prevent stale writes. */
    public static void cancel(File filesDirectory) throws IOException {
        GameStoragePaths paths = new GameStoragePaths(filesDirectory);
        long now = System.currentTimeMillis();
        Operation[] operations = {
            () -> {
                File directory = new ComponentStoragePaths(filesDirectory).getTasksDirectory();
                if (!directory.exists()) return;
                FileComponentTaskRepository repository = new FileComponentTaskRepository(directory);
                for (ComponentTask task : repository.list()) {
                    if (task.getState().shouldRecoverAutomatically()) {
                        repository.save(task.transition(ComponentTaskState.PAUSED,
                            task.getDownloadedBytes(), task.getEtag(), task.getLastModified(),
                            APP_EXIT, APP_EXIT));
                    }
                }
            },
            () -> {
                FileRuntimeSetupTaskRepository repository =
                    new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory());
                for (RuntimeSetupTask task : repository.list()) {
                    if (!task.getState().isTerminal()) repository.save(task.transition(
                        RuntimeSetupTaskState.CANCELLED, APP_EXIT, Math.max(now, task.getUpdatedAt())));
                }
            },
            () -> {
                FilePrefixSetupTaskRepository repository =
                    new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory());
                for (PrefixSetupTask task : repository.list()) {
                    if (!task.getState().isTerminal()) repository.save(task.transition(
                        RuntimeSetupTaskState.CANCELLED, APP_EXIT, Math.max(now, task.getUpdatedAt())));
                }
            },
            () -> {
                FileResetTaskRepository repository =
                    new FileResetTaskRepository(paths.getResetTasksDirectory());
                for (ResetTask task : repository.list()) {
                    if (!task.getState().isTerminal()) repository.save(task.transition(
                        ResetTaskState.CANCELLED, APP_EXIT, Math.max(now, task.getUpdatedAt())));
                }
            },
            () -> {
                FileLaunchTaskRepository repository =
                    new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
                for (LaunchTask task : repository.list()) {
                    if (!task.getState().isTerminal()) repository.save(task.transition(
                        LaunchTaskState.CANCELLED, task.getStage(), task.getProgress(),
                        task.getPid(), task.getExitCode(), APP_EXIT, false, task.getLogRef()));
                }
            },
            () -> {
                FileRootfsBackupTaskRepository repository =
                    new FileRootfsBackupTaskRepository(paths.getBackupTasksDirectory());
                for (RootfsBackupTask task : repository.list()) {
                    if (!task.getState().isTerminal()) repository.save(task.transition(
                        RootfsBackupTaskState.FAILED, APP_EXIT, Math.max(now, task.getUpdatedAt())));
                }
            },
            () -> {
                FileCustomComponentInstallTaskRepository repository =
                    new FileCustomComponentInstallTaskRepository(paths.getCustomComponentInstallTasksDirectory());
                for (CustomComponentInstallTask task : repository.list()) {
                    if (!task.getState().isTerminal()) repository.save(task.transition(
                        CustomComponentInstallTaskState.FAILED, APP_EXIT, Math.max(now, task.getUpdatedAt())));
                }
            }
        };
        IOException failure = null;
        for (Operation operation : operations) {
            try {
                operation.run();
            } catch (IOException error) {
                if (failure == null) failure = error;
                else failure.addSuppressed(error);
            }
        }
        if (failure != null) throw failure;
    }

    private interface Operation {
        void run() throws IOException;
    }
}
