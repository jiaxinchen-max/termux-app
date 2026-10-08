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

    /** READY when the one shared RootFS (see GameStoragePaths.getSharedRootfsDirectory()) is a
     *  complete build -- building/rebuilding it publishes directly into that same live directory
     *  (a same-filesystem mv, see setup_rootfs_runtime.sh), so every ROOTFS_PROOT container always
     *  mounts the one current image; there is no per-container copy, and no separate archive file,
     *  left to go stale or to check instead. Mirrors setup_rootfs_runtime.sh's own
     *  runtime_complete() check exactly -- keep both in sync if either changes. An atomically
     *  published directory has no partially-built state to represent, so unlike glibcState() this
     *  is strictly binary -- INCOMPLETE is never returned. */
    public RuntimeReadinessState rootfsState() {
        File root = paths.getSharedRootfsDirectory();
        boolean complete = new File(root, "usr/bin/env").canExecute() &&
            new File(root, "usr/local/bin/box64").canExecute() &&
            new File(root, "usr/bin/wine").canExecute() &&
            new File(root, "usr/bin/wineboot").canExecute() &&
            new File(root, "usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc").isFile() &&
            new File(root, "etc/games-runtime.properties").isFile() &&
            new File(root, "mnt/games/game").isDirectory() &&
            new File(root, "mnt/games/prefix").isDirectory();
        return complete ? RuntimeReadinessState.READY : RuntimeReadinessState.NOT_READY;
    }
}
