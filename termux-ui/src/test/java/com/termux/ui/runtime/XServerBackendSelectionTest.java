package com.termux.ui.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.GameRuntimeBackendType;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.domain.RuntimeProfile;
import com.termux.ui.domain.XServerBackendType;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;

public class XServerBackendSelectionTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private GameStoragePaths paths() {
        return new GameStoragePaths(temporaryFolder.getRoot());
    }

    private void writeMarker(String content) throws IOException {
        File marker = paths().getXServerBackendMarker();
        assertTrue(marker.getParentFile().isDirectory() || marker.getParentFile().mkdirs());
        try (FileWriter writer = new FileWriter(marker)) { writer.write(content); }
    }

    @Test
    public void defaultsToLegacyWhenNothingWasChosen() {
        assertEquals(XServerBackendType.LEGACY_IN_PROCESS, XServerBackendSelection.read(paths()));
        assertEquals(XServerBackendType.LEGACY_IN_PROCESS, XServerBackendSelection.read(null));
        assertEquals("termux-x11", XServerBackendSelection.hostCapabilityId(paths()));
    }

    @Test
    public void persistsTheChoiceAndFlipsBack() throws Exception {
        XServerBackendSelection.write(paths(), XServerBackendType.XLORIE_EXTERNAL);
        assertEquals(XServerBackendType.XLORIE_EXTERNAL, XServerBackendSelection.read(paths()));
        assertEquals("xlorie", XServerBackendSelection.hostCapabilityId(paths()));
        assertEquals("xlorie\n", new String(java.nio.file.Files.readAllBytes(
            paths().getXServerBackendMarker().toPath())));

        XServerBackendSelection.write(paths(), XServerBackendType.LEGACY_IN_PROCESS);
        assertEquals(XServerBackendType.LEGACY_IN_PROCESS, XServerBackendSelection.read(paths()));
        assertFalse(new File(paths().getRuntimeDirectory(), "x-server-backend.tmp").exists());
    }

    @Test
    public void unknownOrOversizedMarkerMeansLegacy() throws Exception {
        writeMarker("wayland\n");
        assertEquals(XServerBackendType.LEGACY_IN_PROCESS, XServerBackendSelection.read(paths()));
        writeMarker("");
        assertEquals(XServerBackendType.LEGACY_IN_PROCESS, XServerBackendSelection.read(paths()));
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100; i++) huge.append("xlorie");
        writeMarker(huge.toString());
        assertEquals(XServerBackendType.LEGACY_IN_PROCESS, XServerBackendSelection.read(paths()));
    }

    @Test
    public void markerIsTolerantOfSurroundingWhitespace() throws Exception {
        writeMarker("  xlorie \n");
        assertEquals(XServerBackendType.XLORIE_EXTERNAL, XServerBackendSelection.read(paths()));
    }

    @Test
    public void bothBackendsAskForTheSelectedServersCapability() throws Exception {
        RuntimeProfile glibc = new RuntimeProfile("game-1", "glibc_wine_9.3-vanilla", "virgl",
            "dxvk", "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.GLIBC_TERMUX_BOX, "");
        RuntimeProfile rootfs = new RuntimeProfile("game-1", "rootfs_hangover_11.16",
            "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", "1280x720", "INTERMEDIATE",
            Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", "container-game-1");

        Set<String> legacyGlibc = new GlibcTermuxBoxBackend().requiredHostCapabilityIds(glibc, paths());
        Set<String> legacyRootfs = new RootfsProotBackend().requiredHostCapabilityIds(rootfs, paths());
        assertTrue(legacyGlibc.contains("termux-x11") && !legacyGlibc.contains("xlorie"));
        assertTrue(legacyRootfs.contains("termux-x11") && !legacyRootfs.contains("xlorie"));

        XServerBackendSelection.write(paths(), XServerBackendType.XLORIE_EXTERNAL);
        Set<String> xlorieGlibc = new GlibcTermuxBoxBackend().requiredHostCapabilityIds(glibc, paths());
        Set<String> xlorieRootfs = new RootfsProotBackend().requiredHostCapabilityIds(rootfs, paths());
        assertTrue(xlorieGlibc.contains("xlorie") && !xlorieGlibc.contains("termux-x11"));
        assertTrue(xlorieRootfs.contains("xlorie") && !xlorieRootfs.contains("termux-x11"));
        // Everything else a launch needs is unchanged by the choice.
        assertTrue(xlorieGlibc.contains("termux-glibc"));
        assertTrue(xlorieRootfs.contains("proot") && xlorieRootfs.contains("proot-distro"));
        // The path-less overloads keep describing the legacy server.
        assertTrue(new GlibcTermuxBoxBackend().requiredHostCapabilityIds(glibc).contains("termux-x11"));
        assertTrue(new RootfsProotBackend().requiredHostCapabilityIds(rootfs).contains("termux-x11"));
    }
}
