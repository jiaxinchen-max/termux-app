package com.termux.ui.service;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.RuntimeSetupTask;

import java.io.File;

/** Cross-service admission check: a component delivery and RootFS build never overlap. */
final class RuntimeInstallationGate {
    private RuntimeInstallationGate() { }

    static void requireComponentSlot(File filesDirectory, String selfTaskId) throws Exception {
        GameStoragePaths games = new GameStoragePaths(filesDirectory);
        for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(
            games.getRuntimeSetupTasksDirectory()).list()) {
            if (!task.getState().isTerminal()) throw new Exception("runtime_installation_busy");
        }
        for (ComponentTask task : new FileComponentTaskRepository(new ComponentStoragePaths(
            filesDirectory).getTasksDirectory()).list()) {
            if (task.getState().shouldRecoverAutomatically() &&
                !task.getTaskId().equals(selfTaskId)) {
                throw new Exception("runtime_installation_busy");
            }
        }
    }

    static void requireRootfsSlot(File filesDirectory, String selfTaskId) throws Exception {
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

    static void requireResetSlot(File filesDirectory, String selfTaskId) throws Exception {
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
}
