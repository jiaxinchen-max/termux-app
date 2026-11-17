package com.termux.ui.desktop.archive;

import com.termux.ui.desktop.domain.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopArchiveMetadataTest {
    private byte[] archive(DesktopDe de, String theme) {
        DesktopDistro desktop = new DesktopDistro("old", "Named desktop", DesktopRunMode.PROOT, "old-container", de,
            DesktopGlMode.SYSTEM, DesktopGuestGlMode.DEFAULT, "1280x720", 60, DesktopUsbStorageMode.OFF);
        if (de == DesktopDe.HYPRLAND) desktop = desktop.withHyprlandComponents(HyprlandComponents.RUNTIME_ID, theme);
        return DesktopArchiveMetadata.create("com.termux", "aarch64", "resolute", desktop, null, null).encode();
    }
    @Test public void hyprlandFormatDeclaresAndRestoresExactComponents() throws Exception {
        for (String theme : HyprlandComponents.themes()) {
            byte[] bytes = archive(DesktopDe.HYPRLAND, theme);
            assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("\"version\":3"));
            DesktopDistro restored = DesktopArchiveMetadata.decode(bytes, "com.termux", "aarch64").getDesktop();
            assertEquals(HyprlandComponents.RUNTIME_ID, restored.getHyprlandRuntimeId());
            assertEquals(theme, restored.getHyprlandThemeId());
            assertArrayEquals(bytes, DesktopArchiveMetadata.decode(bytes, "com.termux", "aarch64").encode());
        }
    }
    @Test public void existingXfceManifestRemainsVersionTwoWithNoComponentFields() throws Exception {
        byte[] bytes = archive(DesktopDe.XFCE4, "");
        String json = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"version\":2"));
        assertFalse(json.contains("hyprlandThemeId"));
        assertArrayEquals(bytes, DesktopArchiveMetadata.decode(bytes, "com.termux", "aarch64").encode());
    }
    @Test public void nativeResolutionSurvivesArchivesForEveryContainerDesktop() throws Exception {
        for (DesktopDe de : DesktopDe.values()) if (de.isCreatable()) {
            DesktopDistro desktop = new DesktopDistro("output-archive", "Output", DesktopRunMode.PROOT,
                "ubuntu-output-1", de, DesktopGlMode.SYSTEM,
                de == DesktopDe.KWIN_PLASMA || de.isGnomeSession() ? DesktopGuestGlMode.LLVMPIPE : DesktopGuestGlMode.DEFAULT,
                "native", 60, DesktopUsbStorageMode.OFF);
            byte[] bytes = DesktopArchiveMetadata.create("com.termux", "aarch64", "resolute", desktop, null, null).encode();
            DesktopDistro restored = DesktopArchiveMetadata.decode(bytes, "com.termux", "aarch64").getDesktop();
            assertEquals("native", restored.getResolution());
            assertEquals("2400x1080", com.termux.ui.desktop.runtime.DesktopDisplayPolicy.resolveResolution(restored, 2400, 1080));
            assertArrayEquals(bytes, DesktopArchiveMetadata.decode(bytes, "com.termux", "aarch64").encode());
        }
    }

    @Test public void invalidOrAmbiguousComponentsAreRejectedRatherThanDefaulted() throws Exception {
        String valid = new String(archive(DesktopDe.HYPRLAND, HyprlandComponents.CLASSIC), StandardCharsets.UTF_8);
        for (String json : new String[] {
                valid.replace("classic-r7", "unknown"), valid.replace("0.57.0-r7", "0.58.0"),
                valid.replace("\"version\":3", "\"version\":2"),
                valid.replace("\"desktopSchema\":4", "\"desktopSchema\":3"),
                valid.replace(",\"hyprlandThemeId\":\"classic-r7\"", ""),
                valid.replace("\"hyprlandThemeId\":\"classic-r7\"", "\"hyprlandThemeId\":null"),
                valid.replace("\"hyprlandThemeId\":\"classic-r7\"", "\"hyprlandThemeId\":\"classic-r7\",\"hyprlandThemeId\":\"m3ww-v1\""),
                valid.replace("\"exclusions\":", "\"extra\":true,\"exclusions\":") }) {
            try { DesktopArchiveMetadata.decode(json.getBytes(StandardCharsets.UTF_8), "com.termux", "aarch64"); fail(json); }
            catch (IOException expected) { }
        }
    }
}
