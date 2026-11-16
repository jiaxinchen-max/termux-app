package com.termux.ui.desktop.archive;

import com.termux.ui.desktop.domain.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DesktopArchiveLaunchSpecTest {
    @Rule public TemporaryFolder work = new TemporaryFolder();
    private final DesktopArchiveEngineTest.NioFs fs = new DesktopArchiveEngineTest.NioFs();

    private DesktopDistro desktop(DesktopDe de) {
        return new DesktopDistro("new-id", "Restored", DesktopRunMode.PROOT, "new-alias", de,
            DesktopGlMode.SYSTEM, de == DesktopDe.XFCE4 || de == DesktopDe.HYPRLAND ? DesktopGuestGlMode.DEFAULT : DesktopGuestGlMode.LLVMPIPE,
            "1280x720", 60, DesktopUsbStorageMode.OFF);
    }
    private void publish(DesktopArchiveFileSystem filesystem, File target, DesktopDe de,
                         DesktopArchiveLaunchSpec.Identity journal) throws Exception {
        File state = new File(work.getRoot(), "state");
        DesktopArchiveLaunchSpec.publish(filesystem, target, desktop(de), new File(state, "new-id.pid"),
            new File(state, "new-id.status"), new File(work.getRoot(), "new-id.log"), journal);
    }
    @Test public void allRestorableDesktopsHaveFinalContractBeforeLaunching() throws Exception {
        for (DesktopDe de : new DesktopDe[] {DesktopDe.UBUNTU_GNOME, DesktopDe.KWIN_PLASMA, DesktopDe.XFCE4, DesktopDe.HYPRLAND}) {
            File folder = work.newFolder(de.getStorageValue());
            File target = new File(folder, "new-id.spec");
            long[] owned = new long[2];
            publish(fs, target, de, (temporary, device, inode) -> {
                assertFalse(target.exists());
                assertEquals(0, temporary.length());
                owned[0] = device; owned[1] = inode;
            });
            String spec = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            assertTrue(spec.contains("DESKTOP_ID=new-id\n"));
            assertTrue(spec.contains("DESKTOP_PROOT_DISTRO=new-alias\n"));
            assertTrue(spec.contains("DESKTOP_PID_FILE=" + new File(work.getRoot(), "state/new-id.pid") + "\n"));
            assertTrue(spec.contains("DESKTOP_DE=" + de.getStorageValue() + "\n"));
            if (de == DesktopDe.UBUNTU_GNOME) assertTrue(spec.contains("DESKTOP_GNOME_SESSION_PROFILE=ubuntu\n"));
            if (de == DesktopDe.HYPRLAND) {
                assertTrue(spec.contains("DESKTOP_HYPRLAND_RUNTIME_ID=" + HyprlandComponents.RUNTIME_ID + "\n"));
                assertTrue(spec.contains("DESKTOP_HYPRLAND_THEME_ID=" + HyprlandComponents.DEFAULT_THEME + "\n"));
            }
            assertEquals(owned[0], fs.lstat(target).device);
            assertEquals(owned[1], fs.lstat(target).inode);
            assertEquals(1, folder.list().length);
        }
    }
    @Test public void preexistingContractIsNeverOverwritten() throws Exception {
        File folder = work.newFolder("existing"), target = new File(folder, "new-id.spec");
        Files.write(target.toPath(), new byte[] {42});
        try { publish(fs, target, DesktopDe.UBUNTU_GNOME, (file, dev, ino) -> fail()); fail(); }
        catch (IOException expected) { }
        assertArrayEquals(new byte[] {42}, Files.readAllBytes(target.toPath()));
        assertEquals(1, folder.list().length);
    }
    @Test public void racingContractIsPreservedAndOnlyOurTemporaryIsRemoved() throws Exception {
        File folder = work.newFolder("race"), target = new File(folder, "new-id.spec");
        DesktopArchiveEngineTest.NioFs race = new DesktopArchiveEngineTest.NioFs() {
            @Override public void rename(File from, File to) throws IOException {
                Files.write(to.toPath(), new byte[] {99});
                super.rename(from, to);
            }
        };
        try { publish(race, target, DesktopDe.UBUNTU_GNOME, (file, dev, ino) -> { }); fail(); }
        catch (IOException expected) { }
        assertArrayEquals(new byte[] {99}, Files.readAllBytes(target.toPath()));
        assertEquals(1, folder.list().length);
    }
    @Test public void failedOwnershipJournalCannotPublishLaunchableDesktop() throws Exception {
        File folder = work.newFolder("journal"), target = new File(folder, "new-id.spec");
        try { publish(fs, target, DesktopDe.UBUNTU_GNOME, (file, dev, ino) -> { throw new IOException("journal failed"); }); fail(); }
        catch (IOException expected) { assertEquals("journal failed", expected.getMessage()); }
        assertEquals(0, folder.list().length);
    }
}
