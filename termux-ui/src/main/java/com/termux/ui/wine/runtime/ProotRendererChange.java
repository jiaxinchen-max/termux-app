package com.termux.ui.wine.runtime;

import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfileChange;
import com.termux.ui.wine.domain.RuntimeProfileDiff;

import java.io.File;
import java.util.List;

/** Narrow admission for changing a prepared container's launch-time graphics selection. */
public final class ProotRendererChange {
    private ProotRendererChange() { }

    public static boolean isRendererOnly(RuntimeProfile before, RuntimeProfile after) {
        if (before == null || after == null
                || before.getRuntimeBackendType() != GameRuntimeBackendType.ROOTFS_PROOT
                || after.getRuntimeBackendType() != GameRuntimeBackendType.ROOTFS_PROOT
                || com.termux.ui.wine.domain.GameContainer.DEFAULT_ID.equals(after.getContainerId())
                || !before.getId().equals(after.getId())
                || !before.getContainerId().equals(after.getContainerId())) return false;
        List<RuntimeProfileChange> changes = RuntimeProfileDiff.between(before, after);
        return changes.size() == 1 && "graphicsDriver".equals(changes.get(0).getField());
    }

    public static boolean backendFilesReady(File rootfs, String graphics) {
        switch (graphics) {
            case "rootfs-turnip":
                return new File(rootfs, "usr/share/vulkan/icd.d/freedreno_icd.json").isFile();
            case "rootfs-llvmpipe":
            case "rootfs-virgl-mesa":
                return true; // The verified base supplies Mesa; VirGL also needs a host capability.
            default: return false;
        }
    }
}
