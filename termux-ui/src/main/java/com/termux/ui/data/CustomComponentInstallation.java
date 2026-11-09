package com.termux.ui.data;

import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Properties;

/** Validates a published component receipt and its required payload without running it. */
public final class CustomComponentInstallation {
    private CustomComponentInstallation() { }

    public static File directory(GameStoragePaths paths, CustomRuntimeComponent component) {
        if (component.getBackendType() == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
            File glibc = new File(paths.getTermuxPrefixDirectory(), "glibc");
            return new File(component.getKind() == CustomRuntimeComponent.Kind.WINE ? glibc : new File(glibc, "components"), component.getId());
        }
        File version = new File(new File(new com.termux.ui.components.ComponentStoragePaths(
            paths.getTermuxPrefixDirectory().getParentFile()).getInstallDirectory(), component.getId()),
            "versions/1-" + component.getSha256());
        return version;
    }

    public static boolean isAvailable(GameStoragePaths paths, CustomRuntimeComponent component) throws IOException {
        File root = directory(paths, component);
        File receipt = new File(root, ".custom-component.properties");
        if (!receipt.isFile()) return false;
        Properties value = new Properties();
        try (FileInputStream input = new FileInputStream(receipt)) { value.load(input); }
        if (!"1".equals(value.getProperty("schemaVersion")) ||
            !component.getId().equals(value.getProperty("componentId")) ||
            !component.getBackendType().getStorageValue().equals(value.getProperty("runtimeBackend")) ||
            !component.getKind().name().toLowerCase(Locale.ROOT).equals(value.getProperty("kind")) ||
            !(component.getTranslator() == null ? "none" : component.getTranslator().getStorageValue())
                .equals(value.getProperty("runtimeTranslator"))) return false;
        switch (component.getKind()) {
            case WINE: return file(root, "bin/wine") && file(root, "bin/wineboot") && file(root, "bin/wineserver") &&
                (new File(root, "lib/wine").isDirectory() || new File(root, "lib64/wine").isDirectory());
            case BOX64: return file(root, "bin/box64");
            case DXVK: return file(root, "x64/d3d11.dll") && file(root, "x64/dxgi.dll") && file(root, "x32/d3d11.dll") && file(root, "x32/dxgi.dll");
            case VKD3D: return file(root, "x64/d3d12.dll") && file(root, "x64/d3d12core.dll") && file(root, "x86/d3d12.dll") && file(root, "x86/d3d12core.dll");
            case TURNIP: return file(root, "lib/libvulkan_freedreno.so");
            default: return false;
        }
    }

    private static boolean file(File root, String name) throws IOException {
        File file = new File(root, name);
        return file.isFile() && file.length() > 0 &&
            file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator);
    }
}
