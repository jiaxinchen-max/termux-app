package com.termux.localgames.runtime;

import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeProfile;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Existing TermuxBox runtime preserved as the default backend. */
public final class GlibcTermuxBoxBackend implements GameRuntimeBackend {

    private static final Set<String> BASE_COMPONENTS = new LinkedHashSet<>(Arrays.asList(
        "scripts", "glibc-prefix", "box64-binaries", "prefix-apps", "libudev",
        "en-ru-locale", "wine-fonts"));

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
        result.add(profile.getWinePackage());
        result.addAll(profile.getComponentVersions().keySet());
        return result;
    }

    @Override
    public Set<String> requiredHostCapabilityIds(RuntimeProfile profile) {
        requireProfile(profile);
        return java.util.Collections.singleton("termux-x11");
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
