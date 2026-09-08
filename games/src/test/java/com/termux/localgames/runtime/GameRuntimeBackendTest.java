package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.RuntimeProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

public class GameRuntimeBackendTest {

    private static final String SHA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

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

        assertEquals(Collections.singleton("hangover-11.9-debian13-source"), components);
        assertFalse(components.contains("glibc-prefix"));
        assertEquals(3, backend.requiredHostCapabilityIds(rootfsProfile()).size());
        assertTrue(backend.requiredHostCapabilityIds(rootfsProfile()).contains("termux-x11"));
        assertTrue(backend.requiredHostCapabilityIds(rootfsProfile()).contains("proot"));
        assertTrue(backend.requiredHostCapabilityIds(rootfsProfile()).contains("proot-distro"));
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

        File glibc = new GlibcTermuxBoxBackend().resolvePrefix(paths, "game-1");
        File rootfs = new RootfsProotBackend().resolvePrefix(paths, "game-1");

        assertFalse(glibc.equals(rootfs));
        assertTrue(rootfs.getPath().contains("rootfs_proot"));
    }

    @Test
    public void rootfsResolvesOnlyActiveTermuxBuiltContainer() throws Exception {
        File files = temporary.newFolder("component-files");
        ComponentStoragePaths componentPaths = new ComponentStoragePaths(files);
        File packageRoot = new File(new GameStoragePaths(files).getRootfsRuntimeDirectory(),
            "debian-13-games-rootfs");
        File rootfs = new File(new GameStoragePaths(files).getProotDistroContainersDirectory(),
            "games-debian13-hangover119-v1/rootfs");
        assertTrue(rootfs.mkdirs());
        assertTrue(new File(rootfs, "mnt/games/game").mkdirs());
        assertTrue(new File(rootfs, "mnt/games/prefix").mkdirs());
        write(new File(rootfs, "etc/games-runtime.properties"), properties(
            "schemaVersion", "2", "runtimeBackend", "rootfs_proot",
            "architecture", "aarch64",
            "runtimePackages", "hangover-11.9,box64-wine-stable",
            "graphicsDrivers", "rootfs-virgl-mesa,rootfs-llvmpipe",
            "dxWrappers", "rootfs-dxvk,rootfs-wined3d",
            "audioDrivers", "pulseaudio,alsa"));
        write(new File(packageRoot, "versions/v1-aaaaaaaaaaaa.properties"), properties(
            "schemaVersion", "1", "packageName", "debian-13-games-rootfs",
            "version", "1", "recipeSha256", SHA,
            "containerName", "games-debian13-hangover119-v1"));
        write(new File(packageRoot, "active.properties"), properties(
            "schemaVersion", "1", "active", "v1-aaaaaaaaaaaa", "previous", ""));

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
        RuntimeProfile profile = new RuntimeProfile("game-1", "wine-9.3-vanilla-wow64",
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
        RuntimeProfile profile = new RuntimeProfile("game-1", "hangover-11.9",
            "rootfs-virgl-mesa", "rootfs-dxvk", "pulseaudio", "1280x720",
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
        RuntimeProfile virgl = new RuntimeProfile("game-1", "hangover-11.9",
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
        RuntimeProfile profile = new RuntimeProfile("game-1", "hangover-11.9",
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
        RuntimeProfile profile = new RuntimeProfile("game-1", "hangover-11.9",
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
        return new RuntimeProfile("game-1", "hangover-11.9", "rootfs-llvmpipe",
            "rootfs-wined3d", "pulseaudio", "1280x720", "INTERMEDIATE",
            Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL,
            Collections.emptyMap(), GameRuntimeBackendType.ROOTFS_PROOT,
            "debian-13-games-rootfs");
    }

    private static RuntimeProfile glibcProfile() {
        return new RuntimeProfile("game-1", "wine-9.3-vanilla-wow64", "virgl",
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
