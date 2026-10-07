package com.termux.localgames.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Versioned recipe identity; payload bytes are bundled as Games assets. */
public final class RootfsSetupRecipe {
    public static final String DEFAULT_PACKAGE = "debian-13-games-rootfs";
    public static final String DEFAULT_SOURCE = "hangover-11.9-debian13-source";
    /**
     * Every component baked into the one shared RootFS base image, all delivered through the unified
     * component framework (download + sha256 + extract/raw-publish) and installed in-guest by
     * setup-container.sh by content: the Hangover Debian source, the Box64 translator .deb, an mprotect
     * patch overlay for that same Box64 binary (works around Android's W^X SELinux policy denying
     * PROT_EXEC on app-private-storage memory -- needed only by Box64-translated Wine, not Hangover's
     * native ARM64 wine; not yet submitted upstream, see box64-wine-39bit-pitfalls writeup), the
     * selectable DXVK builds (installed under /opt/games-runtime/&lt;id&gt; for per-game dxWrapper use),
     * and a portable vanilla Wine build (installed under /opt/box64-wine for per-game selection of the
     * standalone Box64 translator path, as an alternative to Hangover's bundled translation layer).
     */
    public static final List<String> DEFAULT_BASE_COMPONENTS = Collections.unmodifiableList(
        Arrays.asList(
            DEFAULT_SOURCE,
            "box64-rootfs",
            "box64-rootfs-mprotect-patch",
            "rootfs-dxvk-2.7",
            "rootfs-dxvk-3.1",
            "rootfs-dxvk-1.10.3",
            "box64-wine-10.0"));
    public static final int DEFAULT_VERSION = 10;

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
        if (!DEFAULT_PACKAGE.equals(packageName)) {
            throw new IllegalArgumentException("rootfs_recipe_unknown:" + packageName);
        }
        return new RootfsSetupRecipe(DEFAULT_PACKAGE, DEFAULT_VERSION, DEFAULT_SOURCE,
            DEFAULT_BASE_COMPONENTS);
    }

    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getSourceComponentId() { return sourceComponentId; }
    /** Full ordered set of components the shared base image is built from. */
    public List<String> getBaseComponentIds() { return baseComponentIds; }
    public String getAssetDirectory() { return "local-games/runtime-rootfs"; }
    public String getContainerPrefix() { return "games-debian13-hangover119-v" + version + "-"; }
}
