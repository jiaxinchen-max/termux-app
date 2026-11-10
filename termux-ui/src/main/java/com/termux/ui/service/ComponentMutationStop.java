package com.termux.ui.service;

import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.install.ComponentMaintenance;
import com.termux.ui.data.*;
import com.termux.ui.domain.*;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.runtime.RuntimeEditingStop;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Explicit mutations stop consumers and custom publishers, even if recorded as finished. */
public final class ComponentMutationStop {
    private ComponentMutationStop() { }
    public static void stop(File files, LocalGamesHost host, String id) throws java.io.IOException {
        ComponentMaintenance.requireManageable(id);
        GameStoragePaths paths = new GameStoragePaths(files);
        for (RuntimeProfile profile : new FileRuntimeProfileRepository(paths.getProfilesDirectory()).list()) {
            String box = profile.getEnvironment().get("GAMES_CUSTOM_BOX64_ID");
            boolean related = id.equals(profile.getWinePackage()) || id.equals(box) ||
                id.equals(profile.getGraphicsDriver()) || id.equals(profile.getDxWrapper()) ||
                profile.getComponentVersions().containsKey(id);
            if ((box == null || box.isEmpty()) && "glibc_box64_0.2.8".equals(id) && profile.getRuntimeBackendType() ==
                    com.termux.ui.wine.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX) related = true;
            if ("rootfs_box64_0.4.5".equals(id) && (box == null || box.isEmpty()) &&
                profile.getRuntimeBackendType() == com.termux.ui.wine.domain.GameRuntimeBackendType.ROOTFS_PROOT &&
                !profile.getWinePackage().startsWith("rootfs_hangover_")) related = true;
            if (related) RuntimeEditingStop.game(files, host, profile.getId());
        }
        FileCustomComponentInstallTaskRepository tasks = new FileCustomComponentInstallTaskRepository(
            paths.getCustomComponentInstallTasksDirectory());
        List<String> scopes = new ArrayList<>();
        for (CustomComponentInstallTask task : tasks.list()) if (id.equals(task.getComponentId())) {
            if (!task.getState().isTerminal()) tasks.save(task.transition(
                CustomComponentInstallTaskState.FAILED, "component_mutation_cancelled", System.currentTimeMillis()));
            scopes.add(new File(paths.getCustomComponentInstallSpecsDirectory(), task.getTaskId() + ".installspec").getAbsolutePath());
        }
        // Include component paths to terminate untracked descendants before deleting files.
        scopes.add(new File(files, "usr/glibc/components/" + id).getAbsolutePath());
        scopes.add(new File(files, "usr/glibc/" + id).getAbsolutePath());
        scopes.add(new File(new ComponentStoragePaths(files).getInstallDirectory(), id).getAbsolutePath());
        try { host.forceStopRuntimeProcesses(scopes); }
        catch (com.termux.ui.api.LaunchHostException error) { throw new java.io.IOException(error.getErrorCode(), error); }
    }
}
