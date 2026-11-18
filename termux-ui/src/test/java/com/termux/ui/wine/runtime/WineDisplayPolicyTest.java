package com.termux.ui.wine.runtime;

import com.termux.ui.wine.data.LaunchWindowGeometry;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfilePreset;
import com.termux.ui.wine.domain.RuntimeProfilePresets;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.Arrays;
import static org.junit.Assert.*;

public class WineDisplayPolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void preservesCustomChoicesAndResolvesOnlyNewLaunches() throws Exception {
        assertTrue(Arrays.asList(WineDisplayPolicy.resolutions("1440x900")).contains("1440x900"));
        assertTrue(Arrays.asList(WineDisplayPolicy.resolutions("1440x900")).contains("native"));
        assertEquals("1080x2400", WineDisplayPolicy.resolve("native", 1080, 2400));
        assertEquals("2400x1080", WineDisplayPolicy.resolve("native", 2400, 1080));
        assertEquals("1280x720", WineDisplayPolicy.resolve("1280x720", 0, 0));
        assertEquals("1280x720", WineDisplayPolicy.preparationResolution("native"));
        assertThrows(IllegalArgumentException.class, () -> WineDisplayPolicy.resolve("native", 0, 1080));
        assertThrows(IllegalArgumentException.class, () -> WineDisplayPolicy.requireNumeric("native"));
        assertThrows(IllegalArgumentException.class, () -> WineDisplayPolicy.requireNumeric("65536x1080"));
    }

    @Test public void taskGeometrySurvivesNewReaderAndRejectsMissingOrCorruptRequests() throws Exception {
        File directory = temporary.newFolder();
        LaunchWindowGeometry geometry = new LaunchWindowGeometry(directory);
        geometry.write("launch-1", 1920, 1080);
        assertEquals("1920x1080", new LaunchWindowGeometry(directory).read("launch-1"));
        assertThrows(java.io.IOException.class, () -> geometry.read("missing"));
        java.nio.file.Files.write(new File(directory, "launch-1.window").toPath(), "native".getBytes());
        assertThrows(java.io.IOException.class, () -> geometry.read("launch-1"));
        assertThrows(IllegalArgumentException.class, () -> geometry.read("../outside"));
    }

    @Test public void launchCopyLeavesSavedProfileIntact() {
        RuntimeProfile original = RuntimeProfilePresets.create("game", RuntimeProfilePreset.RECOMMENDED);
        RuntimeProfile launch = WineDisplayPolicy.forLaunch(original, "2400x1080");
        assertEquals("2400x1080", launch.getResolution());
        assertEquals(original.getContainerId(), launch.getContainerId());
        assertEquals(original.getComponentVersions(), launch.getComponentVersions());
        assertNotEquals(original.getResolution(), launch.getResolution());
    }

    @Test public void bothBackendsFreezeNumericGameAndDesktopSpecsWithoutChangingSavedSelection() throws Exception {
        com.termux.ui.data.GameStoragePaths paths = new com.termux.ui.data.GameStoragePaths(temporary.newFolder());
        File root = temporary.newFolder("game");
        assertTrue(new File(root, "game.exe").createNewFile());
        com.termux.ui.wine.domain.Game game = new com.termux.ui.wine.domain.Game("game", "Game",
            root.toURI().toString(), "game.exe", ".", java.util.Collections.emptyList(), "", 0);
        for (com.termux.ui.wine.domain.GameRuntimeBackendType type : com.termux.ui.wine.domain.GameRuntimeBackendType.values()) {
            boolean proot = type == com.termux.ui.wine.domain.GameRuntimeBackendType.ROOTFS_PROOT;
            java.util.Map<String, String> environment = new java.util.LinkedHashMap<>();
            environment.put("TERMUX_BOX_RESOLUTION", "1280x720");
            environment.put("RESOLUTION", "1280x720");
            environment.put("X_SERVER_SCREEN", "1280x720x24");
            environment.put("OTHER", "keep");
            RuntimeProfile original = new RuntimeProfile("game", proot ? "rootfs_wine_test" : "glibc_wine_9.3-vanilla",
                proot ? "rootfs-llvmpipe" : "virgl", proot ? "rootfs-wined3d" : "wined3d", proot ? "pulseaudio" : "alsa", "native",
                "INTERMEDIATE", environment, "xinput", com.termux.ui.domain.LaunchExecutionMode.APP_SHELL,
                java.util.Collections.emptyMap(), type, proot ? "ubuntu-26.04-games-rootfs" : "", "container", false, true);
            com.termux.ui.wine.data.FileRuntimeProfileRepository profiles =
                new com.termux.ui.wine.data.FileRuntimeProfileRepository(paths.getProfilesDirectory());
            profiles.save(original);
            RuntimeProfile launch = WineDisplayPolicy.forLaunch(profiles.find("game").get(), "2400x1080");
            GameRuntimeBackend backend = proot ? new RootfsProotBackend() : new GlibcTermuxBoxBackend();
            for (boolean desktop : new boolean[]{false, true}) {
                com.termux.ui.wine.domain.LaunchSpec spec = new LaunchSpecFactory(paths).create("launch-test", game,
                    launch, root.getPath(), backend, proot ? temporary.getRoot().getPath() : "", desktop);
                File target = new File(temporary.getRoot(), "test.launchspec");
                new com.termux.ui.wine.data.LaunchSpecCodec().write(target, spec);
                spec = new com.termux.ui.wine.data.LaunchSpecCodec().read(target);
                assertEquals("2400x1080", spec.getResolution());
                assertEquals("2400x1080", spec.getEnvironment().get("TERMUX_BOX_RESOLUTION"));
                assertEquals("2400x1080x24", spec.getEnvironment().get("X_SERVER_SCREEN"));
                assertEquals("keep", spec.getEnvironment().get("OTHER"));
                assertEquals(type, spec.getRuntimeBackendType());
                assertEquals(desktop, spec.isDesktopOnly());
                assertFalse(spec.isXinputEnabled()); assertTrue(spec.isDinputEnabled());
            }
            assertEquals("native", profiles.find("game").get().getResolution());
            assertEquals("1280x720", original.getEnvironment().get("RESOLUTION"));
            assertThrows(IllegalArgumentException.class, () -> new LaunchSpecFactory(paths).create(
                "launch-test", game, original, root.getPath(), backend, "", false));
        }
    }
}
