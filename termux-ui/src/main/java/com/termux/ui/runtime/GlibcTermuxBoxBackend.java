package com.termux.ui.runtime;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.components.index.ComponentType;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.GameRuntimeBackendType;
import com.termux.ui.domain.RuntimeProfile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Existing TermuxBox runtime preserved as the default backend. */
public final class GlibcTermuxBoxBackend implements GameRuntimeBackend {

    public static final String TERMUX_GLIBC_ID = "termux-glibc";

    /** Host-managed: installed straight from the Termux glibc packages, so it is not a catalog
     *  component. Only the task record that drives the install needs a descriptor. */
    public static ComponentDescriptor termuxGlibcDescriptor() {
        return new ComponentDescriptor(TERMUX_GLIBC_ID, "runtime", 1,
            "https://github.com/termux/termux-packages", 1L, "", ComponentType.RUNTIME_SUPPORT,
            "Termux glibc", "Termux repository", "Termux glibc runtime installed from packages",
            "Termux GLIBC", false, false, true, "",
            java.util.EnumSet.of(GameRuntimeBackendType.GLIBC_TERMUX_BOX));
    }

    private static final Set<String> BASE_COMPONENTS = new LinkedHashSet<>(Arrays.asList(
        "glibc_box64_0.2.7", "glibc_scripts_29", "glibc_prefix-apps_3", "glibc_fonts_1"));

    @Override
    public GameRuntimeBackendType getType() {
        return GameRuntimeBackendType.GLIBC_TERMUX_BOX;
    }

    @Override
    public Set<String> requiredComponentIds(RuntimeProfile profile) {
        requireProfile(profile);
        Set<String> result = new LinkedHashSet<>(BASE_COMPONENTS);
        result.add(graphicsComponent(profile.getGraphicsDriver()));
        result.add(dxComponent(profile.getDxWrapper()));
        // A "custom-wine-*" package is installed by install_custom_glibc_wine_component.sh
        // straight into $TERMUX_GLIBC_DIR (see GameRuntimeOptionsView's "+ Add custom Wine
        // build..." row) and tracked in the separate CustomRuntimeComponent registry, not the
        // bundled catalog -- adding it here would make launch preflight demand a catalog download
        // that will never exist (mirrors RootfsProotBackend's own "custom-wine-" exclusion).
        if (!profile.getWinePackage().startsWith("custom-wine-")) {
            result.add(profile.getWinePackage());
        }
        result.addAll(profile.getComponentVersions().keySet());
        return result;
    }

    @Override
    public Set<String> requiredHostCapabilityIds(RuntimeProfile profile) {
        return requiredHostCapabilityIds(profile, null);
    }

    @Override
    public Set<String> requiredHostCapabilityIds(RuntimeProfile profile, GameStoragePaths paths) {
        requireProfile(profile);
        return new LinkedHashSet<>(Arrays.asList(
            XServerBackendSelection.hostCapabilityId(paths), TERMUX_GLIBC_ID));
    }

    @Override
    public File resolvePrefix(GameStoragePaths paths, RuntimeProfile profile) throws IOException {
        requireProfile(profile);
        return paths.getContainerPrefixDirectory(profile.getContainerId()).getCanonicalFile();
    }

    @Override
    public String resolveRuntimeRoot(GameStoragePaths paths, ComponentStoragePaths componentPaths,
                                     RuntimeProfile profile) {
        requireProfile(profile);
        return "";
    }

    @Override
    public String getLauncherAssetPath() { return "local-games/start_local_game.sh"; }

    @Override
    public String getLauncherFileName() { return "start_local_game.sh"; }

    private void requireProfile(RuntimeProfile profile) {
        if (profile == null || profile.getRuntimeBackendType() != getType()) {
            throw new IllegalArgumentException("runtime_backend_profile_mismatch");
        }
    }

    private static String graphicsComponent(String selection) {
        String value = selection.toLowerCase(Locale.US);
        if (value.contains("virgl")) return "virgl-mesa";
        if (value.contains("turnip")) return "turnip";
        throw new IllegalArgumentException("renderer_unsupported:" + selection);
    }

    private static String dxComponent(String selection) {
        String value = selection.toLowerCase(Locale.US);
        if (value.contains("wined3d")) return "wined3d";
        if (value.contains("dxvk")) return "dxvk";
        throw new IllegalArgumentException("dxWrapper:" + selection);
    }
}
