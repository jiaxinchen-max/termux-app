package com.termux.localgames.runtime;

import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.RuntimeReadinessState;

import java.io.File;

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

    /** READY when the single shared RootFS base archive (see setup_rootfs_runtime.sh) exists.
     *  Building/rebuilding the base atomically (re)archives it and extracts it live to
     *  GameStoragePaths.getSharedRootfsDirectory() in the same task, so every ROOTFS_PROOT
     *  container always mounts the one current image -- there is no per-container copy left to
     *  go stale. An atomically-renamed archive has no partially-built state to represent, so
     *  unlike glibcState() this is strictly binary -- INCOMPLETE is never returned. */
    public RuntimeReadinessState rootfsState() {
        return paths.getRootfsBaseArchiveZst().isFile() || paths.getRootfsBaseArchiveGz().isFile()
            ? RuntimeReadinessState.READY : RuntimeReadinessState.NOT_READY;
    }
}
