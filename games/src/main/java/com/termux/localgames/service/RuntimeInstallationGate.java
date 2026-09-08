package com.termux.localgames.service;

import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.data.FileRuntimeProvisionTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.RuntimeProvisionTask;

import java.io.File;

/** Cross-service admission check: a component delivery and RootFS build never overlap. */
final class RuntimeInstallationGate {
    private RuntimeInstallationGate() { }

    static void requireComponentSlot(File filesDirectory, String selfTaskId) throws Exception {
        GameStoragePaths games = new GameStoragePaths(filesDirectory);
        for (RuntimeProvisionTask task : new FileRuntimeProvisionTaskRepository(
            games.getRuntimeProvisionTasksDirectory()).list()) {
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
        for (RuntimeProvisionTask task : new FileRuntimeProvisionTaskRepository(
            games.getRuntimeProvisionTasksDirectory()).list()) {
            if (!task.getState().isTerminal() && !task.getTaskId().equals(selfTaskId)) {
                throw new Exception("runtime_installation_busy");
            }
        }
    }
}
