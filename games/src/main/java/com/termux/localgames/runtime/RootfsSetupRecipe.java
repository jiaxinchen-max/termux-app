package com.termux.localgames.runtime;

/** Versioned recipe identity; payload bytes are bundled as Games assets. */
public final class RootfsSetupRecipe {
    public static final String DEFAULT_PACKAGE = "debian-13-games-rootfs";
    public static final String DEFAULT_SOURCE = "hangover-11.9-debian13-source";
    /** DXVK payload baked into the one shared base image alongside the Hangover source. */
    public static final String DEFAULT_DX_COMPONENT = "rootfs-dxvk";
    public static final int DEFAULT_VERSION = 5;

    private final String packageName;
    private final int version;
    private final String sourceComponentId;
    private final String dxComponentId;

    private RootfsSetupRecipe(String packageName, int version, String sourceComponentId,
                              String dxComponentId) {
        this.packageName = packageName;
        this.version = version;
        this.sourceComponentId = sourceComponentId;
        this.dxComponentId = dxComponentId;
    }

    public static RootfsSetupRecipe require(String packageName) {
        if (!DEFAULT_PACKAGE.equals(packageName)) {
            throw new IllegalArgumentException("rootfs_recipe_unknown:" + packageName);
        }
        return new RootfsSetupRecipe(DEFAULT_PACKAGE, DEFAULT_VERSION, DEFAULT_SOURCE,
            DEFAULT_DX_COMPONENT);
    }

    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getSourceComponentId() { return sourceComponentId; }
    public String getDxComponentId() { return dxComponentId; }
    public String getAssetDirectory() { return "local-games/runtime-rootfs"; }
    public String getContainerPrefix() { return "games-debian13-hangover119-v" + version + "-"; }
}
