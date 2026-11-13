package com.termux.ui.desktop.data;

import com.termux.ui.desktop.domain.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HyprlandComponentsRepositoryTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private File directory() { return new File(temp.getRoot(), "distros"); }
    private File home() { return new File(temp.getRoot(), "home"); }
    private FileDesktopDistroRepository repo() { return new FileDesktopDistroRepository(directory(), home()); }
    private DesktopDistro desktop() {
        return new DesktopDistro("desktop-hy", "Hyprland", DesktopRunMode.NATIVE, "", DesktopDe.HYPRLAND,
            DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 30, DesktopUsbStorageMode.OFF);
    }
    private File record() { return new File(directory(), "desktop-hy.properties"); }
    private File config(String name) throws Exception {
        File file = new File(home(), ".termux/hyprland-desktop-hy/config/" + name);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        return file;
    }
    private Properties properties() throws Exception {
        Properties p = new Properties();
        try (FileInputStream in = new FileInputStream(record())) { p.load(in); }
        return p;
    }
    private void write(Properties p) throws Exception {
        try (FileOutputStream out = new FileOutputStream(record())) { p.store(out, null); }
    }
    private byte[] legacy() throws Exception {
        repo().save(desktop());
        Properties p = properties(); p.setProperty("schemaVersion", "3");
        p.remove("hyprlandRuntimeId"); p.remove("hyprlandThemeId"); write(p);
        return Files.readAllBytes(record().toPath());
    }
    @Test public void copiesAndRoundTripsPreserveIndependentSelection() throws Exception {
        DesktopDistro d = desktop().withHyprlandComponents(HyprlandComponents.RUNTIME_ID, HyprlandComponents.CLASSIC)
            .withName("Renamed").withOutput("1920x1080", 60).withRenderers(DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE)
            .withUsbStorage(DesktopUsbStorageMode.OFF);
        repo().save(d); DesktopDistro loaded = repo().find(d.getId()).get();
        assertEquals("4", properties().getProperty("schemaVersion"));
        assertEquals("0.57.0-r7", loaded.getHyprlandRuntimeId());
        assertEquals("classic-r7", loaded.getHyprlandThemeId());
        assertEquals("Renamed", loaded.getName()); assertEquals("1920x1080", loaded.getResolution());
    }
    @Test public void legacyMarkerMigratesOnceWithByteExactBackupAndPreservedUserFiles() throws Exception {
        byte[] old = legacy();
        Files.write(config("desktop-theme").toPath(), "r7\n".getBytes(StandardCharsets.UTF_8));
        File lua = config("hyprland.lua"); Files.write(lua.toPath(), "-- user bindings".getBytes(StandardCharsets.UTF_8));
        assertEquals("classic-r7", repo().find("desktop-hy").get().getHyprlandThemeId());
        assertArrayEquals(old, Files.readAllBytes(new File(record() + ".schema3-backup").toPath()));
        Files.write(config("desktop-theme").toPath(), "m3ww-v1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("classic-r7", repo().find("desktop-hy").get().getHyprlandThemeId());
        assertEquals("-- user bindings", new String(Files.readAllBytes(lua.toPath()), StandardCharsets.UTF_8));
    }
    @Test public void customLegacyLuaKeepsClassicAndMissingLuaKeepsDefault() throws Exception {
        legacy(); Files.write(config("hyprland.lua").toPath(), "-- custom".getBytes(StandardCharsets.UTF_8));
        assertEquals("classic-r7", repo().find("desktop-hy").get().getHyprlandThemeId());
    }
    @Test public void oldM3wwMarkerAndNewDesktopDefaultSelectM3ww() throws Exception {
        legacy(); Files.write(config("desktop-theme").toPath(), "m3ww-v1".getBytes(StandardCharsets.UTF_8));
        assertEquals("m3ww-v1", repo().find("desktop-hy").get().getHyprlandThemeId());
        assertEquals("m3ww-v1", desktop().getHyprlandThemeId());
    }
    @Test public void unknownLegacyMarkerNeverRewritesRecord() throws Exception {
        byte[] old = legacy(); Files.write(config("desktop-theme").toPath(), "unknown".getBytes(StandardCharsets.UTF_8));
        assertThrows(IOException.class, () -> repo().find("desktop-hy"));
        assertArrayEquals(old, Files.readAllBytes(record().toPath()));
        assertFalse(new File(record() + ".schema3-backup").exists());
    }
    @Test public void unknownNewComponentsAndMixedSchemaAreRejectedWithoutRewrite() throws Exception {
        repo().save(desktop()); Properties p = properties(); p.setProperty("hyprlandRuntimeId", "0.57.0-r8"); write(p);
        byte[] invalid = Files.readAllBytes(record().toPath());
        assertThrows(IOException.class, () -> repo().find("desktop-hy"));
        assertArrayEquals(invalid, Files.readAllBytes(record().toPath()));
        p.setProperty("schemaVersion", "3"); write(p);
        assertThrows(IOException.class, () -> repo().find("desktop-hy"));
    }
    @Test public void failedOrUnrelatedTaskCannotReplaceLastGoodSelection() throws Exception {
        FileDesktopDistroRepository saved = repo(); saved.save(desktop());
        HyprlandSelectionTransaction transaction = new HyprlandSelectionTransaction(new File(temp.getRoot(), "pending"), saved);
        DesktopDistro classic = desktop().withHyprlandComponents("0.57.0-r7", "classic-r7");
        transaction.stage(classic, "desktop-install-first");
        assertEquals("m3ww-v1", saved.find("desktop-hy").get().getHyprlandThemeId());
        transaction.finish("desktop-hy", "desktop-install-first", false, d -> true);
        assertEquals("m3ww-v1", saved.find("desktop-hy").get().getHyprlandThemeId());
        transaction.stage(classic, "desktop-install-second");
        transaction.finish("desktop-hy", "desktop-install-wrong", true, d -> true);
        assertEquals("m3ww-v1", saved.find("desktop-hy").get().getHyprlandThemeId());
        transaction.stage(classic, "desktop-install-third");
        transaction.finish("desktop-hy", "desktop-install-third", true, d -> false);
        assertEquals("m3ww-v1", saved.find("desktop-hy").get().getHyprlandThemeId());
        transaction.stage(classic, "desktop-install-fourth");
        transaction.finish("desktop-hy", "desktop-install-fourth", true, d -> true);
        assertEquals("classic-r7", saved.find("desktop-hy").get().getHyprlandThemeId());
    }
}
