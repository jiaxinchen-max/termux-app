package com.termux.localgames.runtime;

import androidx.annotation.Nullable;

import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.data.FileCustomRuntimeComponentRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeProfile;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;

/** Rootless standard-distribution RootFS executed by the Termux host PRoot binary. */
public final class RootfsProotBackend implements GameRuntimeBackend {

    @Override
    public GameRuntimeBackendType getType() { return GameRuntimeBackendType.ROOTFS_PROOT; }

    @Override
    public Set<String> requiredComponentIds(RuntimeProfile profile) {
        return requiredComponentIds(profile, null);
    }

    @Override
    public Set<String> requiredComponentIds(RuntimeProfile profile, GameStoragePaths paths) {
        requireProfile(profile, paths);
        Set<String> result = new LinkedHashSet<>();
        if (profile.getWinePackage().startsWith("hangover-")) {
            // The one shared base image is built from this whole set (Hangover source, Box64,
            // and every selectable DXVK), all delivered through the unified component framework.
            // A game's dxWrapper only picks which baked DXVK to use at launch, so every rootfs
            // profile requires the full set present before the base image can build.
            result.addAll(RootfsSetupRecipe.DEFAULT_BASE_COMPONENTS);
        }
        return result;
    }

    @Override
    public Set<String> requiredHostCapabilityIds(RuntimeProfile profile) {
        return requiredHostCapabilityIds(profile, null);
    }

    @Override
    public Set<String> requiredHostCapabilityIds(RuntimeProfile profile, GameStoragePaths paths) {
        requireProfile(profile, paths);
        Set<String> result = new LinkedHashSet<>();
        result.add("termux-x11");
        result.add("proot");
        result.add("proot-distro");
        if ("rootfs-virgl-mesa".equals(profile.getGraphicsDriver())) {
            result.add("virgl-server");
        }
        return result;
    }

    @Override
    public File resolvePrefix(GameStoragePaths paths, RuntimeProfile profile) throws IOException {
        requireProfile(profile, paths);
        return paths.getContainerPrefixDirectory(profile.getContainerId(), getType())
            .getCanonicalFile();
    }

    @Override
    public File resolveHomeDirectory(GameStoragePaths paths, RuntimeProfile profile)
        throws IOException {
        requireProfile(profile, paths);
        return paths.getContainerHomeDirectory(profile.getContainerId(), getType())
            .getCanonicalFile();
    }

    @Override
    public String resolveRuntimeRoot(GameStoragePaths paths, ComponentStoragePaths componentPaths,
                                     RuntimeProfile profile) throws IOException {
        requireProfile(profile, paths);
        File rootfs = paths.getSharedRootfsDirectory().getCanonicalFile();
        if (!rootfs.isDirectory()) throw new IOException("rootfs_setup_required");
        verifyManifest(rootfs, profile);
        if (!new File(rootfs, "mnt/games/game").isDirectory() ||
            !new File(rootfs, "mnt/games/prefix").isDirectory()) {
            throw new IOException("rootfs_mountpoints_missing");
        }
        return rootfs.getPath();
    }

    @Override
    public String getLauncherAssetPath() { return "local-games/start_rootfs_game.sh"; }

    @Override
    public String getLauncherFileName() { return "start_rootfs_game.sh"; }

    private void requireProfile(RuntimeProfile profile, @Nullable GameStoragePaths paths) {
        if (profile == null || profile.getRuntimeBackendType() != getType()) {
            throw new IllegalArgumentException("runtime_backend_profile_mismatch");
        }
        String runtime = profile.getWinePackage();
        String graphics = profile.getGraphicsDriver();
        String dx = profile.getDxWrapper();
        String audio = profile.getAudioDriver();
        if (!(runtime.startsWith("hangover-") || runtime.startsWith("box64-wine") ||
            runtime.startsWith("custom-wine-"))) {
            throw new IllegalArgumentException("runtime_engine_unsupported:" + runtime);
        }
        // paths == null means a caller that predates custom components (or a unit test) -- skip
        // the registry check rather than hard-failing every such caller; the install flow and
        // shell-side manifest check (verifyManifest) still catch a genuinely bogus/forged id at
        // the points that matter (launch preflight, actual rootfs mount).
        if (runtime.startsWith("custom-wine-") && paths != null &&
            !customComponentExists(runtime, paths)) {
            throw new IllegalArgumentException("runtime_engine_not_installed:" + runtime);
        }
        if (!("rootfs-virgl-mesa".equals(graphics) || "rootfs-llvmpipe".equals(graphics) ||
            "rootfs-turnip".equals(graphics))) {
            throw new IllegalArgumentException("renderer_unsupported:" + graphics);
        }
        if (!("rootfs-wined3d".equals(dx) || isDxvk(dx))) {
            throw new IllegalArgumentException("dx_wrapper_unsupported:" + dx);
        }
        if (!"pulseaudio".equals(audio)) {
            throw new IllegalArgumentException("audio_driver_unsupported:" + audio);
        }
        if ("rootfs-virgl-mesa".equals(graphics) && isDxvk(dx)) {
            throw new IllegalArgumentException(
                "runtime_combination_unsupported:virgl_dxvk");
        }
    }

    private static boolean customComponentExists(String id, GameStoragePaths paths) {
        try {
            return new FileCustomRuntimeComponentRepository(
                paths.getCustomRuntimeComponentsDirectory()).find(id).isPresent();
        } catch (IOException error) {
            return false;
        }
    }


    /** Every versioned DXVK dxWrapper (rootfs-dxvk-2.7, rootfs-dxvk-3.1, ...) is a selectable
     *  Vulkan Direct3D layer; the component id equals the dxWrapper value. */
    private static boolean isDxvk(String dx) {
        return dx != null && dx.startsWith("rootfs-dxvk-");
    }

    private static void verifyManifest(File rootfs, RuntimeProfile profile) throws IOException {
        File file = new File(rootfs, "etc/games-runtime.properties");
        if (!file.isFile()) throw new IOException("rootfs_runtime_manifest_missing");
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        String schemaVersion = properties.getProperty("schemaVersion");
        if (!("1".equals(schemaVersion) || "2".equals(schemaVersion))) {
            throw new IOException("rootfs_runtime_manifest_unsupported");
        }
        if ("2".equals(schemaVersion)) {
            requireValue(properties, "runtimeBackend", "rootfs_proot",
                "rootfs_runtime_backend_mismatch");
            requireValue(properties, "architecture", "aarch64",
                "rootfs_architecture_mismatch");
        }
        requireCapability(properties, "runtimePackages", profile.getWinePackage(),
            "rootfs_runtime_package_missing");
        requireCapability(properties, "graphicsDrivers", profile.getGraphicsDriver(),
            "rootfs_graphics_driver_missing");
        requireCapability(properties, "dxWrappers", profile.getDxWrapper(),
            "rootfs_dx_wrapper_missing");
        if ("2".equals(schemaVersion)) {
            requireCapability(properties, "audioDrivers", profile.getAudioDriver(),
                "rootfs_audio_driver_missing");
        }
    }

    private static void requireCapability(Properties properties, String key, String expected,
                                          String error) throws IOException {
        String value = properties.getProperty(key, "");
        for (String item : value.split(",", -1)) {
            if (expected.equals(item)) return;
        }
        throw new IOException(error + ":" + expected);
    }

    private static void requireValue(Properties properties, String key, String expected,
                                     String error) throws IOException {
        if (!expected.equals(properties.getProperty(key))) throw new IOException(error);
    }

    private static void requireContained(File parent, File child, String error) throws IOException {
        if (!(child.equals(parent) || child.getPath().startsWith(parent.getPath() + File.separator))) {
            throw new IOException(error);
        }
    }
}
