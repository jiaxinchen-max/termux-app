package com.termux.localgames.runtime;

import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.RuntimeReadinessState;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

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

    /** READY when the shared rootfs template (see setup_rootfs_runtime.sh) that new
     *  containers get cloned from is itself intact; INCOMPLETE when a template directory exists
     *  but its build never finished (interrupted install); NOT_READY when no template exists at
     *  all. Deliberately independent of whether any GameContainer already exists -- an
     *  already-cloned container keeps working off its own files even after the shared template
     *  it came from is reset or replaced (see isRootfsContainerStale for that per-container
     *  case). */
    public RuntimeReadinessState rootfsState() {
        if (anyRootfsTemplateComplete()) return RuntimeReadinessState.READY;
        return anyRootfsTemplateExists()
            ? RuntimeReadinessState.INCOMPLETE : RuntimeReadinessState.NOT_READY;
    }

    /** Whether the given ROOTFS_PROOT container's active build was cloned from a template that
     *  is no longer the live one (reset, or replaced by a different recipe). Purely
     *  informational -- the container's own already-cloned files keep working regardless.
     *  False if the container has no active build at all (nothing to compare). */
    public boolean isRootfsContainerStale(String containerId, String rootfsPackage) {
        try {
            Optional<RootfsRuntimeInstallation> installation =
                new RootfsRuntimeInstallationReader(paths).readActive(containerId, rootfsPackage);
            if (!installation.isPresent()) return false;
            File template = new File(paths.getProotDistroContainersDirectory(),
                "tmpl-" + installation.get().getRecipeSha256().substring(0, 16));
            return !isRootfsBuildComplete(new File(template, "rootfs"));
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** Mirrors setup_rootfs_runtime.sh's own runtime_complete() check, applied to any
     *  "tmpl-<recipeSha256 prefix>" pseudo-container under proot-distro/containers -- recipes
     *  change rarely enough in practice that a name-pattern scan (rather than resolving the
     *  exact current recipeSha256, which needs Context + file I/O this class doesn't have) is
     *  an acceptable approximation. */
    private boolean anyRootfsTemplateComplete() {
        File[] entries = paths.getProotDistroContainersDirectory().listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            if (!entry.getName().startsWith("tmpl-")) continue;
            if (isRootfsBuildComplete(new File(entry, "rootfs"))) return true;
        }
        return false;
    }

    private boolean anyRootfsTemplateExists() {
        File[] entries = paths.getProotDistroContainersDirectory().listFiles();
        if (entries == null) return false;
        for (File entry : entries) {
            if (entry.getName().startsWith("tmpl-")) return true;
        }
        return false;
    }

    private static boolean isRootfsBuildComplete(File root) {
        return new File(root, "usr/bin/env").canExecute()
            && new File(root, "usr/local/bin/box64").canExecute()
            && new File(root, "usr/bin/wine").canExecute()
            && new File(root, "usr/bin/wineboot").canExecute()
            && new File(root, "usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc").isFile()
            && new File(root, "etc/games-runtime.properties").isFile()
            && new File(root, "mnt/games/game").isDirectory()
            && new File(root, "mnt/games/prefix").isDirectory();
    }
}
