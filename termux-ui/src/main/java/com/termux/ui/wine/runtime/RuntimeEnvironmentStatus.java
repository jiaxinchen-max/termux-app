package com.termux.ui.wine.runtime;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.domain.RuntimeReadinessState;

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

    /** Checks base markers and required tools verified by install_termux_glibc_runtime.sh.
     *  No base markers is NOT_READY even if shared tools are preinstalled; READY also
     *  requires nonempty XRandR, executable Python/archive tools and verified shared Chinese locale data.
     *  Partial or missing prerequisites are INCOMPLETE. */
    public RuntimeReadinessState glibcState() {
        File prefix = paths.getTermuxPrefixDirectory();
        File glibc = new File(prefix, "glibc");
        File xrandr = new File(glibc, "lib/libXrandr.so.2");
        boolean[] markers = {
            new File(glibc, "lib/ld-linux-aarch64.so.1").isFile(),
            new File(glibc, "lib/libc.so.6").isFile(),
            new File(prefix, "bin/grun").canExecute(),
            new File(glibc, "lib/libX11.so.6").isFile(),
            new File(glibc, "lib/libXrender.so.1").isFile(),
            xrandr.isFile() && xrandr.length() > 0,
            new File(glibc, "lib/libfreetype.so.6").isFile(),
        };
        int present = 0;
        for (boolean marker : markers) if (marker) present++;
        if (present == 0) return RuntimeReadinessState.NOT_READY;
        for (String tool : new String[] {"7z", "zip", "unzip", "tar", "gzip", "bzip2", "xz", "zstd"}) {
            File command = new File(prefix, "bin/" + tool);
            if (!command.isFile() || command.length() == 0 || !command.canExecute()) return RuntimeReadinessState.INCOMPLETE;
        }
        if (present == markers.length
            && new File(prefix, "bin/python").canExecute()
            && NativeGlibcLocale.isReady(glibc)) return RuntimeReadinessState.READY;
        return RuntimeReadinessState.INCOMPLETE;
    }

    /** READY when the one shared RootFS (see GameStoragePaths.getSharedRootfsDirectory()) is a
     *  complete build -- building/rebuilding it publishes directly into that same live directory
     *  (a same-filesystem mv, see setup_rootfs_runtime.sh), so every ROOTFS_PROOT container always
     *  mounts the one current image; there is no per-container copy, and no separate archive file,
     *  left to go stale or to check instead. Mirrors setup_rootfs_runtime.sh's own
     *  runtime_complete() check exactly -- keep both in sync if either changes. Existing partial
     *  data, an interrupted build or a receipt whose image was removed requires explicit recovery. */
    public RuntimeReadinessState rootfsState() {
        File root = paths.getSharedRootfsDirectory();
        boolean complete = new File(root, "usr/bin/env").canExecute() &&
            externalLayoutOrLegacyBox64(root) &&
            new File(root, "usr/bin/wine").canExecute() &&
            new File(root, "usr/bin/wineboot").canExecute() &&
            new File(root, "usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc").isFile() &&
            new File(root, "etc/games-runtime.properties").isFile() &&
            new File(root, "mnt/games/game").isDirectory() &&
            new File(root, "mnt/games/prefix").isDirectory();
        if (complete) return RuntimeReadinessState.READY;
        if (paths.getSharedRootfsContainerDirectory().exists()
            || paths.getRootfsBuildContainerDirectory().exists()
            || new File(paths.getTemplateArchiveCacheDirectory(), "games-rootfs-base.recipe").exists()) {
            return RuntimeReadinessState.INCOMPLETE;
        }
        return RuntimeReadinessState.NOT_READY;
    }
    private boolean externalLayoutOrLegacyBox64(File root) {
        try {
            if (ProotPreparedRuntime.usesExternalLayout(root)) return true;
        } catch (java.io.IOException error) { return false; }
        return new File(root, "opt/box64/rootfs_box64_0.4.5/bin/box64").canExecute();
    }

}
