package com.termux.ui.wine.api;

import android.content.Context;

import androidx.annotation.NonNull;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FileGameContainerRepository;
import com.termux.ui.wine.runtime.GameContainerFactory;
import java.io.IOException;

import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;

/** Required preparation shared by configuration Save and runtime-options Save. */
public final class RuntimeWarmup {

    private RuntimeWarmup() {}

    /** A renderer-only Save can reuse the prepared image, bindings and C drive. */
    @androidx.annotation.Nullable
    public static String warmAfterSave(@NonNull Context context, RuntimeProfile before,
                                      @NonNull RuntimeProfile profile) throws IOException {
        WineCDriveBackupTasks.requireAvailable(context.getFilesDir());
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        if (com.termux.ui.wine.runtime.ProotRendererChange.isRendererOnly(before, profile)) {
            com.termux.ui.wine.runtime.RuntimeEditingStop.game(context.getFilesDir(),
                com.termux.ui.api.LocalGames.requireHost(context), profile.getId());
            synchronized (com.termux.ui.wine.runtime.RuntimeEditingStop.LOCK) {
                boolean prepared;
                try { prepared = new com.termux.ui.wine.runtime.GameEditorReadiness(paths).isReady(profile); }
                catch (IOException incomplete) { prepared = false; }
                boolean ready = prepared && com.termux.ui.wine.runtime.ProotRendererChange.backendFilesReady(
                    paths.getSharedRootfsDirectory(), profile.getGraphicsDriver());
                // Reuse the launch policy, including VirGL/DXVK compatibility and host bridge checks.
                for (String capability : new com.termux.ui.wine.runtime.RootfsProotBackend()
                        .requiredHostCapabilityIds(profile, paths)) {
                    if (!com.termux.ui.api.LocalGames.requireHost(context)
                            .isRuntimeComponentAvailable(capability)) ready = false;
                }
                if (ready) {
                    new FileGameContainerRepository(paths.getContainersDirectory())
                        .save(GameContainerFactory.fromProfile(profile, paths));
                    return null;
                }
            }
        }
        return warm(context, profile);
    }

    /** Returns a visible preparation task; scheduling failures must be reported by the caller. */
    @NonNull
    public static String warm(@NonNull Context context, @NonNull RuntimeProfile profile) throws IOException {
        WineCDriveBackupTasks.requireAvailable(context.getFilesDir());
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
            return PrefixSetupTasks.enqueue(context, profile.getId());
        }
        if (profile.getRuntimeBackendType() != GameRuntimeBackendType.ROOTFS_PROOT
            || com.termux.ui.wine.domain.GameContainer.DEFAULT_ID.equals(profile.getContainerId())) {
            throw new IOException("runtime_container_mode_mismatch");
        }
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        new FileGameContainerRepository(paths.getContainersDirectory())
            .save(GameContainerFactory.fromProfile(profile, paths));
        return RuntimeSetupTasks.enqueue(context, profile.getRootfsPackage(), profile.getContainerId());
    }
}
