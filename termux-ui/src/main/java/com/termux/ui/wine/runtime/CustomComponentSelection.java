package com.termux.ui.wine.runtime;

import com.termux.ui.wine.domain.ProotBox64Versions;
import androidx.annotation.Nullable;

import com.termux.ui.data.FileCustomRuntimeComponentRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeTranslator;

import java.io.IOException;

/** Validates the exact registered component selected by one game, independently of catalog state. */
final class CustomComponentSelection {
    static void validate(RuntimeProfile profile, @Nullable GameStoragePaths paths) {
        if (HangoverEmulationMode.appliesTo(profile)) HangoverEmulationMode.selected(profile);
        require(profile.getWinePackage(), CustomRuntimeComponent.Kind.WINE, profile, paths);
        require(profile.getGraphicsDriver(), CustomRuntimeComponent.Kind.TURNIP, profile, paths);
        String dx = profile.getDxWrapper();
        if (dx.startsWith("custom-")) {
            require(dx, dx.startsWith("custom-vkd3d-") ? CustomRuntimeComponent.Kind.VKD3D :
                CustomRuntimeComponent.Kind.DXVK, profile, paths);
        }
        String box64 = profile.getEnvironment().getOrDefault("GAMES_CUSTOM_BOX64_ID", "");
        if (!box64.isEmpty()) {
            if (!(box64.matches("custom-box64-[A-Za-z0-9._-]+") ||
                (profile.getRuntimeBackendType() == GameRuntimeBackendType.GLIBC_TERMUX_BOX &&
                    NativeBox64Versions.isBuiltIn(box64)) ||
                (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT &&
                    ProotBox64Versions.isBuiltIn(box64)))) {
                throw new IllegalArgumentException("box64_selection_unsupported:" + box64);
            }
            require(box64, CustomRuntimeComponent.Kind.BOX64, profile, paths);
            RuntimeTranslator translator = GameContainerProfileResolver.resolveTranslator(
                profile.getWinePackage(), paths);
            if (translator != RuntimeTranslator.BOX64) {
                throw new IllegalArgumentException("box64_translator_mismatch:" + box64);
            }
        }
    }

    private static void require(String id, CustomRuntimeComponent.Kind kind, RuntimeProfile profile,
                                @Nullable GameStoragePaths paths) {
        if (!id.startsWith("custom-")) return;
        String prefix = "custom-" + kind.name().toLowerCase(java.util.Locale.US) + "-";
        if (!id.startsWith(prefix) || !id.matches("[A-Za-z0-9._-]{1,128}") ||
            id.length() == prefix.length()) {
            throw new IllegalArgumentException("custom_component_kind_mismatch:" + id);
        }
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT &&
            (kind == CustomRuntimeComponent.Kind.TURNIP || kind == CustomRuntimeComponent.Kind.VKD3D)) {
            throw new IllegalArgumentException("custom_component_backend_unsupported:" + id);
        }
        if (paths == null) return;
        try {
            CustomRuntimeComponent component = new FileCustomRuntimeComponentRepository(
                paths.getCustomRuntimeComponentsDirectory()).find(id).orElseThrow(() ->
                    new IllegalArgumentException("custom_component_not_installed:" + id));
            if (component.getKind() != kind || component.getBackendType() != profile.getRuntimeBackendType()) {
                throw new IllegalArgumentException("custom_component_backend_mismatch:" + id);
            }
            if (!com.termux.ui.data.CustomComponentInstallation.isAvailable(paths, component)) {
                throw new IllegalArgumentException("custom_component_payload_missing:" + id);
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("custom_component_lookup_failed:" + id, error);
        }
    }

    private CustomComponentSelection() {}
}
