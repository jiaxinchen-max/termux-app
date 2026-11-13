package com.termux.ui.service;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.domain.RuntimeSetupTask;

import java.io.File;

/** Cross-service admission check: a component delivery and RootFS build never overlap. */
public final class RuntimeInstallationGate {
    private RuntimeInstallationGate() { }

    public static void requireComponentSlot(File filesDirectory, String selfTaskId) throws Exception {
        com.termux.ui.wine.api.WineCDriveBackupTasks.requireAvailable(filesDirectory);
        GameStoragePaths games = new GameStoragePaths(filesDirectory);
        requirePrefixSlot(filesDirectory, selfTaskId, true);
        for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(
            games.getRuntimeSetupTasksDirectory()).list()) {
            if (!task.getState().isTerminal() && !ownsDependency(filesDirectory, task.getTaskId(), selfTaskId)) {
                throw new Exception("runtime_installation_busy");
            }
        }
        for (ComponentTask task : new FileComponentTaskRepository(new ComponentStoragePaths(
            filesDirectory).getTasksDirectory()).list()) {
            if (task.getState().shouldRecoverAutomatically() &&
                !task.getTaskId().equals(selfTaskId)) {
                throw new Exception("runtime_installation_busy");
            }
        }
    }

    public static void requireRootfsSlot(File filesDirectory, String selfTaskId) throws Exception {
        requirePrefixSlot(filesDirectory, selfTaskId, false);
        com.termux.ui.wine.api.WineCDriveBackupTasks.requireAvailable(filesDirectory);
        for (ComponentTask task : new FileComponentTaskRepository(new ComponentStoragePaths(
            filesDirectory).getTasksDirectory()).list()) {
            if (task.getState().shouldRecoverAutomatically()
                && !ownsDependency(filesDirectory, selfTaskId, task.getTaskId())) {
                throw new Exception("runtime_installation_busy");
            }
        }
        GameStoragePaths games = new GameStoragePaths(filesDirectory);
        for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(
            games.getRuntimeSetupTasksDirectory()).list()) {
            if (!task.getState().isTerminal() && !task.getTaskId().equals(selfTaskId)) {
                throw new Exception("runtime_installation_busy");
            }
        }
    }

    public static void requireResetSlot(File filesDirectory, String selfTaskId) throws Exception {
        requirePrefixSlot(filesDirectory, selfTaskId, false);
        com.termux.ui.wine.api.WineCDriveBackupTasks.requireAvailable(filesDirectory);
        for (ComponentTask task : new FileComponentTaskRepository(new ComponentStoragePaths(
            filesDirectory).getTasksDirectory()).list()) {
            if (task.getState().shouldRecoverAutomatically()) {
                throw new Exception("runtime_installation_busy");
            }
        }
        GameStoragePaths games = new GameStoragePaths(filesDirectory);
        for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(
            games.getRuntimeSetupTasksDirectory()).list()) {
            if (!task.getState().isTerminal() && !task.getTaskId().equals(selfTaskId)) {
                throw new Exception("runtime_installation_busy");
            }
        }
    }
    private static void requirePrefixSlot(File files, String taskId, boolean allowDependency) throws Exception {
        for (com.termux.ui.wine.domain.PrefixSetupTask task :
            new com.termux.ui.wine.data.FilePrefixSetupTaskRepository(
                new GameStoragePaths(files).getPrefixSetupTasksDirectory()).list()) {
            if (!task.getState().isTerminal() && !task.getTaskId().equals(taskId)
                && !(allowDependency && ownsDependency(files, task.getTaskId(), taskId)))
                throw new Exception("runtime_installation_busy");
        }
    }

    public static void registerDependency(File files, String parent, String child) throws java.io.IOException {
        File file = dependencyFile(files, parent, child);
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) {
            throw new java.io.IOException("runtime_dependency_directory_failed");
        }
        if (!file.createNewFile() && !file.isFile()) throw new java.io.IOException("runtime_dependency_record_failed");
    }

    public static boolean ownsDependency(File files, String parent, String child) {
        return child != null && dependencyFile(files, parent, child).isFile();
    }

    private static File dependencyFile(File files, String parent, String child) {
        if (!parent.matches("[A-Za-z0-9._-]{1,128}") || !child.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid_runtime_dependency_id");
        }
        return new File(files, "front/runtime/setup/dependencies/" + parent + "/" + child);
    }

}
