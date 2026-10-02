package com.termux.localgames.runtime;

import androidx.annotation.Nullable;

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

    /** READY when the single shared rootfs base archive (see setup_rootfs_runtime.sh) that new
     *  containers are extracted from exists; NOT_READY otherwise. An atomically-renamed archive
     *  has no partially-built state to represent, so unlike glibcState() this is strictly binary
     *  -- INCOMPLETE is never returned. Deliberately independent of whether any GameContainer
     *  already exists -- an already-cloned container keeps working off its own files even after
     *  the shared base it came from is reset or rebuilt (see isRootfsContainerStale for that
     *  per-container case). */
    public RuntimeReadinessState rootfsState() {
        return paths.getRootfsBaseArchiveZst().isFile() || paths.getRootfsBaseArchiveGz().isFile()
            ? RuntimeReadinessState.READY : RuntimeReadinessState.NOT_READY;
    }

    /** Whether the given ROOTFS_PROOT container's active build was cloned from a base archive
     *  that is no longer the live one (reset, or rebuilt from a different recipe). Purely
     *  informational -- the container's own already-cloned files keep working regardless.
     *  False if the container has no active build at all (nothing to compare). */
    public boolean isRootfsContainerStale(String containerId, String rootfsPackage) {
        try {
            Optional<RootfsRuntimeInstallation> installation =
                new RootfsRuntimeInstallationReader(paths).readActive(containerId, rootfsPackage);
            if (!installation.isPresent()) return false;
            return !installation.get().getRecipeSha256().equals(currentBaseRecipeSha256());
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    /** The recipeSha256 recorded alongside the current base archive, or null when no archive (or
     *  no readable sidecar) exists -- in which case every container reads as stale, matching the
     *  old "no valid current template to compare against" behavior. */
    @Nullable
    private String currentBaseRecipeSha256() {
        File recipe = paths.getRootfsBaseRecipeFile();
        if (!recipe.isFile()) return null;
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.FileReader(recipe))) {
            String value = reader.readLine();
            if (value == null) return null;
            value = value.trim();
            return value.matches("[0-9a-f]{64}") ? value : null;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }
}
