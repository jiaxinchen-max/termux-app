package com.termux.localgames.runtime;

/** Versioned recipe identity; payload bytes are bundled as Games assets. */
public final class RootfsProvisionRecipe {
    public static final String DEFAULT_PACKAGE = "debian-13-games-rootfs";
    public static final String DEFAULT_SOURCE = "hangover-11.9-debian13-source";
    public static final int DEFAULT_VERSION = 3;

    private final String packageName;
    private final int version;
    private final String sourceComponentId;

    private RootfsProvisionRecipe(String packageName, int version, String sourceComponentId) {
        this.packageName = packageName;
        this.version = version;
        this.sourceComponentId = sourceComponentId;
    }

    public static RootfsProvisionRecipe require(String packageName) {
        if (!DEFAULT_PACKAGE.equals(packageName)) {
            throw new IllegalArgumentException("rootfs_recipe_unknown:" + packageName);
        }
        return new RootfsProvisionRecipe(DEFAULT_PACKAGE, DEFAULT_VERSION, DEFAULT_SOURCE);
    }

    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getSourceComponentId() { return sourceComponentId; }
    public String getAssetDirectory() { return "local-games/runtime-rootfs"; }
    public String getContainerPrefix() { return "games-debian13-hangover119-v" + version + "-"; }
}
