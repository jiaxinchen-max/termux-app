package com.termux.ui.wine.api;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.data.RuntimeProfileBinding;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.domain.RuntimeSetupTask;

import java.io.File;
import java.io.IOException;

/** Live installation identity shared by card clicks and mutation admission. */
public final class ActiveWineInstallation {
    public final String taskId;
    public final boolean rootfs;
    private ActiveWineInstallation(String taskId, boolean rootfs) {
        this.taskId = taskId;
        this.rootfs = rootfs;
    }
    public static ActiveWineInstallation find(File files, String gameId) throws IOException {
        synchronized (com.termux.ui.wine.runtime.RuntimeEditingStop.LOCK) {
            GameStoragePaths paths = new GameStoragePaths(files);
            for (PrefixSetupTask task : new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).list())
                if (task.getGameId().equals(gameId) && !task.getState().isTerminal())
                    return new ActiveWineInstallation(task.getTaskId(), false);
            FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
            RuntimeProfileBinding profile = new com.termux.ui.wine.importer.GameImportDrafts(files)
                .editingProfiles(gameId).findBinding(gameId).orElse(null);
            if (profile != null && profile.getRuntimeBackendType() ==
                com.termux.ui.wine.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
                for (PrefixSetupTask task : new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).list()) {
                    if (task.getState().isTerminal()) continue;
                    RuntimeProfileBinding owner = profiles.findBinding(task.getGameId()).orElse(null);
                    if (owner != null && owner.getRuntimeBackendType() == profile.getRuntimeBackendType()
                        && owner.getContainerId().equals(profile.getContainerId())
                        && owner.getWinePackage().equals(profile.getWinePackage()))
                        return new ActiveWineInstallation(task.getTaskId(), false);
                }
            }
            if (profile != null && profile.getRuntimeBackendType() ==
                com.termux.ui.wine.domain.GameRuntimeBackendType.ROOTFS_PROOT) for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(
                paths.getRuntimeSetupTasksDirectory()).list())
                if (task.getContainerId().equals(profile.getContainerId()) && !task.getState().isTerminal())
                    return new ActiveWineInstallation(task.getTaskId(), true);
            return null;
        }
    }
}
