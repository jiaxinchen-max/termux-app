package com.termux.ui.activity;

import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeTranslator;

/** One scope policy for component management and per-game selection. */
public final class RuntimeComponentVersions {
    private RuntimeComponentVersions() { }

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
        return supports(kind, backend) && entry.getKind() == kind && entry.getBackendType() == backend &&
            (kind != CustomRuntimeComponent.Kind.WINE || locked == null || entry.getTranslator() == locked);
    }

    public static boolean accepts(ComponentDescriptor entry, CustomRuntimeComponent.Kind kind,
                                  GameRuntimeBackendType backend, RuntimeTranslator locked) {
        if (!supports(kind, backend) || !entry.supportsBackend(backend)) return false;
        String id = entry.getId();
        if (backend == GameRuntimeBackendType.ROOTFS_PROOT) {
            switch (kind) {
                case WINE:
                    if (id.equals("rootfs_hangover_11.16")) return locked == null || locked == RuntimeTranslator.HANGOVER;
                    return (id.startsWith("rootfs_wine_") || id.startsWith("rootfs_proton_")) &&
                        (locked == null || locked == RuntimeTranslator.BOX64);
                case BOX64: return false; // The shared runtime supplies its explicitly patched build.
                case DXVK: return id.startsWith("rootfs_dxvk_");
                default: return false;
            }
        }
        switch (kind) {
            case WINE: return id.startsWith("glibc_wine_") || id.startsWith("glibc_proton_");
            case BOX64: return id.startsWith("glibc_box64_");
            case DXVK: case VKD3D: return id.equals("dxvk");
            case TURNIP: return id.equals("turnip");
            default: return false;
        }
    }

    /** Bundled Native driver/DLL archives contain several versions; their family id is not selectable. */
    public static String catalogSelectionId(ComponentDescriptor entry, CustomRuntimeComponent.Kind kind,
                                            GameRuntimeBackendType backend) {
        if (backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX && kind != CustomRuntimeComponent.Kind.WINE) return null;
        return entry.getProfileValue().isEmpty() ? entry.getId() : entry.getProfileValue();
    }
}
