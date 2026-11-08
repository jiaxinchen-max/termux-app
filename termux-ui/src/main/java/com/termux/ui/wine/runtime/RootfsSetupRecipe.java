package com.termux.ui.wine.runtime;

import java.util.Arrays;
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
    /**
     * Every component baked into the one shared RootFS base image, all delivered through the unified
     * component framework (download + sha256 + extract/raw-publish) and installed in-guest by
     * setup-container.sh by content: the Hangover Debian source, the Box64 translator .deb, an mprotect
     * patch overlay for that same Box64 binary (works around Android's W^X SELinux policy denying
     * PROT_EXEC on app-private-storage memory -- needed only by Box64-translated Wine, not Hangover's
     * native ARM64 wine; not yet submitted upstream, see box64-wine-39bit-pitfalls writeup), the
     * selectable DXVK builds (installed under /opt/games-runtime/&lt;id&gt; for per-game dxWrapper use),
     * and two portable Wine builds for the standalone Box64 translator path (installed under
     * /opt/box64-wine and /opt/box64-proton respectively, as alternatives to Hangover's bundled
     * translation layer and to each other).
     */
    public static final List<String> DEFAULT_BASE_COMPONENTS = Collections.unmodifiableList(
        Arrays.asList(
            DEFAULT_SOURCE,
            "rootfs_box64_0.4.5",
            "rootfs_dxvk_2.7",
            "rootfs_dxvk_3.1",
            "rootfs_dxvk_1.10.3",
            BOX64_WINE_COMPONENT,
            BOX64_PROTON_COMPONENT));
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
        if (version != 10 && version != 11 && version != EXTERNAL_COMPONENTS_VERSION) {
            throw new IllegalArgumentException("rootfs_recipe_version_unsupported:" + version);
        }
        return new RootfsSetupRecipe(DEFAULT_PACKAGE, version, version == 12 ? IMAGE_COMPONENT : DEFAULT_SOURCE,
            version == 10 ? DEFAULT_BASE_COMPONENTS : Collections.singletonList(version == 12 ? IMAGE_COMPONENT : DEFAULT_SOURCE));
    }

    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getSourceComponentId() { return sourceComponentId; }
    /** Full ordered set of components the shared base image is built from. */
    public List<String> getBaseComponentIds() { return baseComponentIds; }
    /** Keep the exact legacy host bytes when replaying a v10 task; new protocol support
     *  must not silently change that task's recipe hash. */
    public String getSetupScriptAssetPath() {
        return version == 10 ? "local-games/runtime-rootfs/setup_rootfs_runtime_v10.sh" :
            "local-games/setup_rootfs_runtime.sh";
    }
    public String getAssetDirectory() {
        return version == 10 ? "local-games/runtime-rootfs" : (version == 11 ? "local-games/runtime-rootfs-v11" : "local-games/runtime-rootfs-v12");
    }
    public String getContainerPrefix() { return "games-debian13-hangover119-v" + version + "-"; }
}
