package com.termux.localgames.runtime;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Versioned recipe identity; payload bytes are bundled as Games assets. */
public final class RootfsSetupRecipe {
    public static final String DEFAULT_PACKAGE = "debian-13-games-rootfs";
    /** Resolved against the latest upstream Hangover release at every RootFS rebuild instead of
     *  being pinned to a fixed version -- see UpstreamWineReleaseResolver /
     *  RootfsRuntimeComponentPreparer. The id itself never needs a version bump again. */
    public static final String DEFAULT_SOURCE = "rootfs_hangover_11.16";
    /** Also resolved against upstream's latest release at every RootFS rebuild (Kron4ek's
     *  vanilla Wine build, used by the standalone-Box64-translator path) -- see
     *  UpstreamWineReleaseResolver / RootfsRuntimeComponentPreparer. */
    public static final String BOX64_WINE_COMPONENT = "rootfs_wine_10.0";
    /** Also resolved against upstream's latest release at every RootFS rebuild (Kron4ek's own
     *  "Proton" build -- Valve's Proton wine patches without Proton's python wrapper or bundled
     *  DXVK/vkd3d-proton, same box64-translated shape as BOX64_WINE_COMPONENT) -- see
     *  UpstreamWineReleaseResolver / RootfsRuntimeComponentPreparer. */
    public static final String BOX64_PROTON_COMPONENT = "rootfs_proton_11.0-2";
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
    public static final int DEFAULT_VERSION = 10;

    /** True for the components resolved against upstream's latest release at every rebuild. Their
     *  entries in the bundled catalog are only offline fallbacks (an older pinned version and sha256),
     *  so the version and checksum actually installed legitimately differ from the catalog's. The
     *  rebuild already verified what it installed, so callers must not compare these against the
     *  catalog entry. */
    public static boolean isResolvedAtRebuild(String componentId) {
        return DEFAULT_SOURCE.equals(componentId)
            || BOX64_WINE_COMPONENT.equals(componentId)
            || BOX64_PROTON_COMPONENT.equals(componentId);
    }

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
