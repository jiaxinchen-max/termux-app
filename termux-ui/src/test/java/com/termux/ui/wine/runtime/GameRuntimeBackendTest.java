package com.termux.ui.wine.runtime;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GameRuntimeBackendTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void registryContainsIndependentDefaultBackends() {
        GameRuntimeBackendRegistry registry = GameRuntimeBackendRegistry.createDefault();

        assertEquals(2, registry.snapshot().size());
        assertTrue(registry.require(GameRuntimeBackendType.GLIBC_TERMUX_BOX)
            instanceof GlibcTermuxBoxBackend);
        assertTrue(registry.require(GameRuntimeBackendType.ROOTFS_PROOT)
            instanceof RootfsProotBackend);
    }

    @Test
    public void rootfsRequirementsUseVerifiedSourceAndTermuxBuilder() {
        RootfsProotBackend backend = new RootfsProotBackend();

        Set<String> components = backend.requiredComponentIds(rootfsProfile());

        // The default image and only the selected optional DXVK are required.
        assertTrue(components.contains(RootfsSetupRecipe.IMAGE_COMPONENT));
        assertFalse(components.contains("rootfs_hangover_11.16"));
        assertFalse(components.contains("rootfs_box64_0.4.5"));
        assertFalse(components.contains("rootfs_dxvk_2.7"));
        assertFalse(components.contains(GlibcTermuxBoxBackend.TERMUX_GLIBC_ID));
        assertEquals(2, backend.requiredHostCapabilityIds(rootfsProfile()).size());
        assertTrue(backend.requiredHostCapabilityIds(rootfsProfile()).contains("termux-x11"));
        assertTrue(backend.requiredHostCapabilityIds(rootfsProfile()).contains("proot"));
        assertFalse(backend.requiredHostCapabilityIds(rootfsProfile()).contains("proot-distro"));
    }

    @Test
    public void rootfsBaseComponentsComeFromTheUnifiedFramework() {
        RootfsProotBackend backend = new RootfsProotBackend();
        RuntimeProfile withDxvk = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-llvmpipe", "rootfs_dxvk_2.7", "pulseaudio", "1280x720", "INTERMEDIATE",
            Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL,
            Collections.emptyMap(), GameRuntimeBackendType.ROOTFS_PROOT,
            "debian-13-games-rootfs", "container-game-1");

        // Optional components stay independent; an unrelated DXVK or translator is not pulled.
        Set<String> components = backend.requiredComponentIds(withDxvk);

        assertFalse(components.contains("rootfs_box64_0.4.5"));
        assertTrue(components.contains("rootfs_dxvk_2.7"));
        assertFalse(components.contains("rootfs_dxvk_3.1"));
        assertFalse(components.contains("rootfs_dxvk_1.10.3"));
    }

    @Test
    public void bothBackendsRequireTheIndependentX11Bridge() {
        assertTrue(new GlibcTermuxBoxBackend().requiredHostCapabilityIds(glibcProfile())
            .contains("termux-x11"));
        assertTrue(new RootfsProotBackend().requiredHostCapabilityIds(rootfsProfile())
            .contains("termux-x11"));
    }

    @Test
    public void prefixesAreSeparatedByBackend() throws Exception {
        File files = temporary.newFolder("files");
        GameStoragePaths paths = new GameStoragePaths(files);

        File glibc = new GlibcTermuxBoxBackend().resolvePrefix(paths, glibcProfile());
        File rootfs = new RootfsProotBackend().resolvePrefix(paths, rootfsProfile());

        assertFalse(glibc.equals(rootfs));
        assertTrue(rootfs.getPath().contains("rootfs_proot"));
    }

    @Test
    public void rootfsResolvesTheSharedLiveRootfs() throws Exception {
        File files = temporary.newFolder("component-files");
        ComponentStoragePaths componentPaths = new ComponentStoragePaths(files);
        File rootfs = new GameStoragePaths(files).getSharedRootfsDirectory();
        assertTrue(rootfs.mkdirs());
        assertTrue(new File(rootfs, "mnt/games/game").mkdirs());
        assertTrue(new File(rootfs, "mnt/games/prefix").mkdirs());
        write(new File(rootfs, "etc/wine-runtime.properties"), properties(
            "schemaVersion", "2", "runtimeBackend", "rootfs_proot",
            "architecture", "aarch64",
            "runtimePackages", "rootfs_hangover_11.16,box64-wine-stable",
            "graphicsDrivers", "rootfs-virgl-mesa,rootfs-llvmpipe",
            "dxWrappers", "rootfs-dxvk,rootfs-wined3d",
            "audioDrivers", "pulseaudio,alsa"));

        String resolved = new RootfsProotBackend().resolveRuntimeRoot(
            new GameStoragePaths(files), componentPaths, rootfsProfile());

        assertEquals(rootfs.getCanonicalPath(), resolved);

        assertTrue(new File(rootfs, "mnt/games/game").delete());
        try {
            new RootfsProotBackend().resolveRuntimeRoot(
                new GameStoragePaths(files), componentPaths, rootfsProfile());
            fail("Expected missing RootFS mountpoint");
        } catch (IOException expected) {
            assertEquals("rootfs_mountpoints_missing", expected.getMessage());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void glibcBackendRejectsRootfsProfile() {
        new GlibcTermuxBoxBackend().requiredComponentIds(rootfsProfile());
    }

    @Test
    public void glibcBackendDoesNotPretendVortekIsTurnip() {
        RuntimeProfile profile = new RuntimeProfile("game-1", "glibc_wine_9.3-vanilla",
            "vortek", "dxvk", "alsa", "1280x720", "INTERMEDIATE",
            Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL,
            Collections.emptyMap(), GameRuntimeBackendType.GLIBC_TERMUX_BOX, "");

        try {
            new GlibcTermuxBoxBackend().requiredComponentIds(profile);
            fail("Expected unsupported renderer");
        } catch (IllegalArgumentException expected) {
            assertEquals("renderer_unsupported:vortek", expected.getMessage());
        }
    }

    @Test
    public void rootfsBackendRejectsVirglWithDxvk() {
        RuntimeProfile profile = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-virgl-mesa", "rootfs_dxvk_2.7", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");

        try {
            new RootfsProotBackend().requiredComponentIds(profile);
            fail("Expected unsupported runtime combination");
        } catch (IllegalArgumentException expected) {
            assertEquals("runtime_combination_unsupported:virgl_dxvk",
                expected.getMessage());
        }
    }

    @Test
    public void rootfsVirglRequiresHostServerButLlvmpipeDoesNot() {
        RuntimeProfile virgl = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-virgl-mesa", "rootfs-wined3d", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");

        assertTrue(new RootfsProotBackend().requiredHostCapabilityIds(virgl)
            .contains("virgl-server"));
        assertFalse(new RootfsProotBackend().requiredHostCapabilityIds(rootfsProfile())
            .contains("virgl-server"));
    }

    @Test
    public void rootfsBackendRejectsCapabilitiesNotImplementedByLauncher() {
        RuntimeProfile profile = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-vortek", "rootfs-wined3d", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");

        try {
            new RootfsProotBackend().requiredComponentIds(profile);
            fail("Expected unsupported renderer");
        } catch (IllegalArgumentException expected) {
            assertEquals("renderer_unsupported:rootfs-vortek", expected.getMessage());
        }
    }

    @Test
    public void rootfsBackendRejectsAudioNotImplementedByLauncher() {
        RuntimeProfile profile = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-virgl-mesa", "rootfs-wined3d", "alsa", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs");

        try {
            new RootfsProotBackend().requiredComponentIds(profile);
            fail("Expected unsupported audio driver");
        } catch (IllegalArgumentException expected) {
            assertEquals("audio_driver_unsupported:alsa", expected.getMessage());
        }
    }

    private static RuntimeProfile rootfsProfile() {
        return new RuntimeProfile("game-1", "rootfs_hangover_11.16", "rootfs-llvmpipe",
            "rootfs-wined3d", "pulseaudio", "1280x720", "INTERMEDIATE",
            Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL,
            Collections.emptyMap(), GameRuntimeBackendType.ROOTFS_PROOT,
            "debian-13-games-rootfs", "container-game-1");
    }

    private static RuntimeProfile glibcProfile() {
        return new RuntimeProfile("game-1", "glibc_wine_9.3-vanilla", "virgl",
            "dxvk", "pulseaudio", "1280x720", "INTERMEDIATE",
            Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL,
            Collections.emptyMap(), GameRuntimeBackendType.GLIBC_TERMUX_BOX, "");
    }

    private static Properties properties(String... pairs) {
        Properties result = new Properties();
        for (int index = 0; index < pairs.length; index += 2) {
            result.setProperty(pairs[index], pairs[index + 1]);
        }
        return result;
    }

    private static void write(File file, Properties properties) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            properties.store(output, null);
        }
    }
}
