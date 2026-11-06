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
