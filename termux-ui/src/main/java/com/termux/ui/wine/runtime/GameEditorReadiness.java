package com.termux.ui.wine.runtime;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeReadinessState;
import com.termux.ui.wine.domain.RuntimeSetupTask;
import com.termux.ui.wine.domain.RuntimeSetupTaskState;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

/** Read-only admission for editor actions. A shared runtime is never proof of a game's Save. */
public final class GameEditorReadiness {
    interface EnvironmentProbe { boolean isReady(RuntimeProfile profile) throws IOException; }

    private final GameStoragePaths paths;
    private final EnvironmentProbe environment;

    public GameEditorReadiness(GameStoragePaths paths) {
        this.paths = paths;
        this.environment = this::environmentReady;
    }

    GameEditorReadiness(GameStoragePaths paths, EnvironmentProbe environment) {
        this.paths = paths;
        this.environment = environment;
    }

    public boolean isReady(RuntimeProfile profile) throws IOException {
        if (profile == null) return false;
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
            PrefixSetupTask latest = null;
            for (PrefixSetupTask task : new FilePrefixSetupTaskRepository(
                    paths.getPrefixSetupTasksDirectory()).list()) {
                if (!profile.getId().equals(task.getGameId())) continue;
                if (!task.getState().isTerminal()) return false;
                if (latest == null || task.getCreatedAt() > latest.getCreatedAt()
                    || (task.getCreatedAt() == latest.getCreatedAt()
                        && (task.getState() != RuntimeSetupTaskState.SUCCEEDED
                            || !profile.getWinePackage().equals(task.getWinePackage())))) latest = task;
            }
            return latest != null && latest.getState() == RuntimeSetupTaskState.SUCCEEDED
                && profile.getWinePackage().equals(latest.getWinePackage())
                && environment.isReady(profile);
        }
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
            RuntimeSetupTask latest = null;
            for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(
                    paths.getRuntimeSetupTasksDirectory()).list()) {
                if (!profile.getContainerId().equals(task.getContainerId()) || task.isBaseOnly()) continue;
                if (!task.getState().isTerminal()) return false;
                if (latest == null || task.getCreatedAt() > latest.getCreatedAt()
                    || (task.getCreatedAt() == latest.getCreatedAt()
                        && (task.getState() != RuntimeSetupTaskState.SUCCEEDED
                            || !profile.getRootfsPackage().equals(task.getPackageName())))) latest = task;
            }
            return latest != null && latest.getState() == RuntimeSetupTaskState.SUCCEEDED
                && profile.getRootfsPackage().equals(latest.getPackageName())
                && environment.isReady(profile);
        }
        return false;
    }

    private boolean environmentReady(RuntimeProfile profile) throws IOException {
        RuntimeEnvironmentStatus status = new RuntimeEnvironmentStatus(paths);
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
            File prefix = paths.getNativePrefixDirectory(profile.getContainerId(), profile.getWinePackage());
            return status.glibcState() == RuntimeReadinessState.READY
                && prefixComplete(prefix)
                && new File(prefix, ".termux-box-bootstrap-done").isFile()
                && new File(prefix, ".update-timestamp").exists()
                && new File(prefix, "termux-boxmeta/wine-fonts-v2").isFile()
                && marker(new File(prefix, ".games-runtime/prepared-identity"))
                && marker(new File(prefix, ".games-runtime/preparation-script-identity"))
                && profile.getWinePackage().equals(firstLine(new File(prefix, ".termux-box-wine-package")));
        }
        if (status.rootfsState() != RuntimeReadinessState.READY) return false;
        ProotPreparedRuntime.require(paths, profile);
        File prefix = paths.getContainerPrefixDirectory(profile.getContainerId(),
            GameRuntimeBackendType.ROOTFS_PROOT);
        return prefixComplete(prefix)
            && nonempty(new File(prefix, ".games-runtime/preparation-recipe"))
            && nonempty(new File(prefix, ".games-runtime/runtime"))
            && firstLine(new File(prefix, ".games-runtime/runtime")).equals(
                paths.getSharedRootfsDirectory().getCanonicalPath() + "|" + profile.getWinePackage())
            && !new File(prefix, ".games-runtime/wine-dlls-pending").exists();
    }

    private static boolean prefixComplete(File prefix) {
        return nonempty(new File(prefix, "system.reg")) && nonempty(new File(prefix, "user.reg"))
            && new File(prefix, "drive_c").isDirectory();
    }

    private static boolean nonempty(File file) { return file.isFile() && file.length() > 0; }
    private static boolean marker(File file) { return file.isFile() && file.length() == 65; }

    private static String firstLine(File file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line = reader.readLine();
            return line == null ? "" : line;
        }
    }
}
