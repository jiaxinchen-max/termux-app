package com.termux.localgames.runtime;

import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.GameContainerRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;

import java.io.File;
import java.io.IOException;

/** Independent ready/not-ready checks for the two runtime backends -- replaces the single
 *  combined {@code LocalGamesHost.isRuntimeAvailable()} boolean, which cannot represent two
 *  unrelated environments. */
public final class RuntimeEnvironmentStatus {
    private final GameStoragePaths paths;

    public RuntimeEnvironmentStatus(GameStoragePaths paths) {
        if (paths == null) throw new IllegalArgumentException("paths required");
        this.paths = paths;
    }

    /** Mirrors the exact marker files install_termux_glibc_runtime.sh verifies after install. */
    public boolean isGlibcReady() {
        File prefix = paths.getTermuxPrefixDirectory();
        File glibc = new File(prefix, "glibc");
        return new File(glibc, "lib/ld-linux-aarch64.so.1").isFile()
            && new File(glibc, "lib/libc.so.6").isFile()
            && new File(prefix, "bin/grun").canExecute()
            && new File(glibc, "lib/libX11.so.6").isFile()
            && new File(glibc, "lib/libXrender.so.1").isFile()
            && new File(glibc, "lib/libfreetype.so.6").isFile();
    }

    /** Ready when at least one independent container has an activated RootFS build. */
    public boolean isRootfsReady() {
        GameContainerRepository containers = new FileGameContainerRepository(
            paths.getContainersDirectory());
        RootfsRuntimeInstallationReader reader = new RootfsRuntimeInstallationReader(paths);
        try {
            for (GameContainer container : containers.list()) {
                if (container.getBackendType() != GameRuntimeBackendType.ROOTFS_PROOT) continue;
                if (reader.readActive(container.getId(), container.getRootfsPackage()).isPresent()) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }
}
