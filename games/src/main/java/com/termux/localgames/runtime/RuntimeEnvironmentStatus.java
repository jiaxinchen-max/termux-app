package com.termux.localgames.runtime;

import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.GameContainerRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeReadinessState;

import java.io.File;
import java.io.IOException;

/** Independent readiness checks for the two runtime backends -- replaces the single
 *  combined {@code LocalGamesHost.isRuntimeAvailable()} boolean, which cannot represent two
 *  unrelated environments. */
public final class RuntimeEnvironmentStatus {
    private final GameStoragePaths paths;

    public RuntimeEnvironmentStatus(GameStoragePaths paths) {
        if (paths == null) throw new IllegalArgumentException("paths required");
        this.paths = paths;
    }

    /** Counts the exact marker files install_termux_glibc_runtime.sh verifies after install:
     *  all present is READY, none present is NOT_READY, and a partial count is INCOMPLETE
     *  (interrupted install, or one still in progress). */
    public RuntimeReadinessState glibcState() {
        File prefix = paths.getTermuxPrefixDirectory();
        File glibc = new File(prefix, "glibc");
        boolean[] markers = {
            new File(glibc, "lib/ld-linux-aarch64.so.1").isFile(),
            new File(glibc, "lib/libc.so.6").isFile(),
            new File(prefix, "bin/grun").canExecute(),
            new File(glibc, "lib/libX11.so.6").isFile(),
            new File(glibc, "lib/libXrender.so.1").isFile(),
            new File(glibc, "lib/libfreetype.so.6").isFile(),
        };
        int present = 0;
        for (boolean marker : markers) if (marker) present++;
        if (present == 0) return RuntimeReadinessState.NOT_READY;
        if (present == markers.length) return RuntimeReadinessState.READY;
        return RuntimeReadinessState.INCOMPLETE;
    }

    /** READY when at least one independent container has an activated RootFS build;
     *  INCOMPLETE when a container exists but none has completed activation (created but
     *  never finished, or a build that was interrupted); NOT_READY when no container exists. */
    public RuntimeReadinessState rootfsState() {
        GameContainerRepository containers = new FileGameContainerRepository(
            paths.getContainersDirectory());
        RootfsRuntimeInstallationReader reader = new RootfsRuntimeInstallationReader(paths);
        boolean anyContainer = false;
        try {
            for (GameContainer container : containers.list()) {
                if (container.getBackendType() != GameRuntimeBackendType.ROOTFS_PROOT) continue;
                anyContainer = true;
                if (reader.readActive(container.getId(), container.getRootfsPackage()).isPresent()) {
                    return RuntimeReadinessState.READY;
                }
            }
        } catch (IOException ignored) {
            return RuntimeReadinessState.NOT_READY;
        }
        return anyContainer ? RuntimeReadinessState.INCOMPLETE : RuntimeReadinessState.NOT_READY;
    }
}
