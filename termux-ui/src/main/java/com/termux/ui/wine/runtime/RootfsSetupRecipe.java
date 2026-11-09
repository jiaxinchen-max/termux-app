package com.termux.ui.wine.runtime;

import java.util.Collections;
import java.util.List;

/** Versioned recipe identity; payload bytes are bundled as Games assets. */
public final class RootfsSetupRecipe {
    public static final String DEFAULT_PACKAGE = "debian-13-games-rootfs";
    /** Stable game Wine selection; the preinstalled image has its own component identity. */
    public static final String DEFAULT_SOURCE = "rootfs_hangover_11.16";
    /** Kron4ek's vanilla Wine build, used by the standalone-Box64-translator path. */
    public static final String BOX64_WINE_COMPONENT = "rootfs_wine_10.0";
    /** Kron4ek's "Proton" build -- Valve's Proton wine patches without Proton's python wrapper or
     *  bundled DXVK/vkd3d-proton, same box64-translated shape as BOX64_WINE_COMPONENT. */
    public static final String BOX64_PROTON_COMPONENT = "rootfs_proton_11.0";
    // New installations restore the preinstalled v12 image; old tasks are rejected by setup.
    public static final int DEFAULT_VERSION = 12;
    public static final int EXTERNAL_COMPONENTS_VERSION = 12;
    public static final String IMAGE_COMPONENT = "rootfs_debian13_hangover_pdb2_minimal";


    private final String packageName;
    private final int version;
    private final String sourceComponentId;
    private final List<String> baseComponentIds;

    private RootfsSetupRecipe(String packageName, int version, String sourceComponentId,
                             List<String> baseComponentIds) {
        this.packageName = packageName;
        this.version = version;
        this.sourceComponentId = sourceComponentId;
        this.baseComponentIds = baseComponentIds;
    }

    public static RootfsSetupRecipe require(String packageName) {
        return require(packageName, DEFAULT_VERSION);
    }

    public static RootfsSetupRecipe require(String packageName, int version) {
        if (!DEFAULT_PACKAGE.equals(packageName)) {
            throw new IllegalArgumentException("rootfs_recipe_unknown:" + packageName);
        }
        if (version != DEFAULT_VERSION) {
            throw new IllegalArgumentException("rootfs_recipe_version_unsupported:" + version);
        }
        return new RootfsSetupRecipe(DEFAULT_PACKAGE, version, IMAGE_COMPONENT,
            Collections.singletonList(IMAGE_COMPONENT));
    }

    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getSourceComponentId() { return sourceComponentId; }
    /** Full ordered set of components the shared base image is built from. */
    public List<String> getBaseComponentIds() { return baseComponentIds; }
    public String getSetupScriptAssetPath() { return "local-games/setup_rootfs_runtime.sh"; }
    public String getAssetDirectory() { return "local-games/runtime-rootfs-v12"; }
    public String getContainerPrefix() { return "games-debian13-hangover119-v" + version + "-"; }
}
