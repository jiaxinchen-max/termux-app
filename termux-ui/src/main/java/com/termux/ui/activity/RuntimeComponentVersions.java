package com.termux.ui.activity;

import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeTranslator;
import com.termux.ui.wine.runtime.NativeBox64Versions;

/** One scope policy for component management and per-game selection. */
public final class RuntimeComponentVersions {
    private RuntimeComponentVersions() { }

    public static final String PREBUILT_PROOT_BOX64 = "rootfs_box64_0.4.5";

    /** Resolve the displayed default without changing the game's stored selection. */
    public static String selectedBox64Id(GameRuntimeBackendType backend, String value) {
        if (backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX) return NativeBox64Versions.selectedId(value);
        return value == null || value.isEmpty() ? PREBUILT_PROOT_BOX64 : value;
    }

    public static final String PREINSTALLED_HANGOVER = "rootfs_hangover_11.16";

    public static boolean isPreinstalled(String id, GameRuntimeBackendType backend) {
        return backend == GameRuntimeBackendType.ROOTFS_PROOT && PREINSTALLED_HANGOVER.equals(id);
    }

    public static boolean allowsManualInstall(CustomRuntimeComponent.Kind kind,
            GameRuntimeBackendType backend, RuntimeTranslator locked) {
        return !(kind == CustomRuntimeComponent.Kind.WINE && backend == GameRuntimeBackendType.ROOTFS_PROOT
            && locked == RuntimeTranslator.HANGOVER);
    }

    public static String displayName(CustomRuntimeComponent.Kind kind) {
        switch (kind) {
            case WINE: return "Wine";
            case BOX64: return "Box64";
            case TURNIP: return "Turnip";
            default: return kind.name();
        }
    }

    public static boolean supports(CustomRuntimeComponent.Kind kind, GameRuntimeBackendType backend) {
        if (kind == null || backend == null) return false;
        return backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX ||
            (backend == GameRuntimeBackendType.ROOTFS_PROOT &&
                (kind == CustomRuntimeComponent.Kind.WINE || kind == CustomRuntimeComponent.Kind.BOX64 ||
                    kind == CustomRuntimeComponent.Kind.DXVK));
    }

    public static boolean accepts(CustomRuntimeComponent entry, CustomRuntimeComponent.Kind kind,
                                  GameRuntimeBackendType backend, RuntimeTranslator locked) {
        return !(backend == GameRuntimeBackendType.ROOTFS_PROOT && kind == CustomRuntimeComponent.Kind.WINE
                && entry.getTranslator() == RuntimeTranslator.HANGOVER)
            && supports(kind, backend) && entry.getKind() == kind && entry.getBackendType() == backend &&
            (kind != CustomRuntimeComponent.Kind.WINE || locked == null || entry.getTranslator() == locked);
    }

    public static boolean accepts(ComponentDescriptor entry, CustomRuntimeComponent.Kind kind,
                                  GameRuntimeBackendType backend, RuntimeTranslator locked) {
        boolean nativeBox64 = kind == CustomRuntimeComponent.Kind.BOX64 &&
            backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX && NativeBox64Versions.isBuiltIn(entry.getId());
        boolean prootBox64 = kind == CustomRuntimeComponent.Kind.BOX64 &&
            backend == GameRuntimeBackendType.ROOTFS_PROOT && PREBUILT_PROOT_BOX64.equals(entry.getId());
        if ((entry.isHidden() && !nativeBox64 && !prootBox64) || !supports(kind, backend) || !entry.supportsBackend(backend)) return false;
        String id = entry.getId();
        if (backend == GameRuntimeBackendType.ROOTFS_PROOT) {
            switch (kind) {
                case WINE:
                    return (id.startsWith("rootfs_wine_") || id.startsWith("rootfs_proton_")) &&
                        (locked == null || locked == RuntimeTranslator.BOX64);
                case BOX64: return prootBox64 && (locked == null || locked == RuntimeTranslator.BOX64);
                case DXVK: return id.startsWith("rootfs_dxvk_");
                default: return false;
            }
        }
        switch (kind) {
            case WINE: return id.startsWith("glibc_wine_") || id.startsWith("glibc_proton_");
            case BOX64: return NativeBox64Versions.isBuiltIn(id);
            case DXVK: case VKD3D: return id.equals("dxvk");
            case TURNIP: return id.equals("turnip");
            default: return false;
        }
    }

    /** Bundled Native driver/DLL archives contain several versions; their family id is not selectable. */
    public static String catalogSelectionId(ComponentDescriptor entry, CustomRuntimeComponent.Kind kind,
                                            GameRuntimeBackendType backend) {
        if (backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX && kind != CustomRuntimeComponent.Kind.WINE &&
            !(kind == CustomRuntimeComponent.Kind.BOX64 && NativeBox64Versions.isBuiltIn(entry.getId()))) return null;
        return entry.getProfileValue().isEmpty() ? entry.getId() : entry.getProfileValue();
    }
}
