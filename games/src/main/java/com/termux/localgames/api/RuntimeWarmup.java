package com.termux.localgames.api;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeProfile;

/** Opportunistically starts a game's runtime setup as soon as its profile is persisted (at
 *  import or when runtime options are saved), instead of waiting for the user to tap Launch --
 *  so a later launch finds the work already done or in flight. */
public final class RuntimeWarmup {
    private static final String TAG = "RuntimeWarmup";

    private RuntimeWarmup() {}

    /** Never throws: failures (including "another setup is already running") are swallowed and
     *  simply fall back to today's at-launch setup. Returns the enqueued (or already-active)
     *  task's id so the caller can show its live console, or null if nothing was enqueued. */
    @Nullable
    public static String warm(@NonNull Context context, @NonNull RuntimeProfile profile) {
        try {
            if (profile.getRuntimeBackendType() == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
                return PrefixSetupTasks.enqueue(context, profile.getId());
            } else if (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
                return RuntimeSetupTasks.enqueue(context, profile.getRootfsPackage(),
                    profile.getContainerId());
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Background runtime warm-up failed, will retry at launch", error);
        }
        return null;
    }
}
