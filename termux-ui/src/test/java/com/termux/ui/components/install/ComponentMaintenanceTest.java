package com.termux.ui.components.install;

import com.termux.ui.components.ComponentStoragePaths;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class ComponentMaintenanceTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private void write(File file) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), "sentinel".getBytes(StandardCharsets.UTF_8));
    }
    @Test public void uninstallOneGraphicsVersionPreservesOtherVersionsPrefixAndDownloads() throws Exception {
        File files = temp.newFolder();
        String id = "native-turnip-v4";
        File payload = new File(files, "usr/" + ComponentMaintenance.variantArchive(id));
        File expanded = new File(files, "usr/glibc/components/" + id + "/driver.so");
        File copy = new File(new ComponentStoragePaths(files).getInstallDirectory(), id + "/versions/test/payload");
        File other = new File(files, "usr/" + ComponentMaintenance.variantArchive("native-turnip-v5"));
        File prefix = new File(files, "front/wine/wine-example/drive_c/save.dat");
        File cache = new File(files, "downloads/" + id + "-1.archive");
        for (File file : new File[]{payload, expanded, copy, other, prefix, cache}) write(file);
        for (String root : new String[]{"termux-box", "opt"}) {
            for (String suffix : new String[]{"", "_lists", "_md5"})
                write(new File(files, "usr/glibc/" + root + "/package-manager/installed/" + id + suffix));
        }
        new ComponentMaintenance(files).uninstall(id);
        for (File file : new File[]{payload, expanded, copy}) assertFalse(file.toString(), file.exists());
        for (File file : new File[]{other, prefix, cache}) assertTrue(file.toString(), file.isFile());
        assertFalse(new File(files, "usr/glibc/opt/package-manager/installed/" + id).exists());
    }
    @Test public void uninstallBox64DoesNotTrustOldDeletionReceipt() throws Exception {
        File files = temp.newFolder();
        File selected = new File(files, "usr/glibc/components/glibc_box64_0.4.4/bin/box64");
        File other = new File(files, "usr/glibc/components/glibc_box64_0.2.8/bin/box64");
        write(selected); write(other);
        File receipt = new File(files, "usr/glibc/termux-box/package-manager/installed/glibc_box64_0.4.4_lists");
        write(receipt);
        Files.write(receipt.toPath(), other.getAbsolutePath().getBytes(StandardCharsets.UTF_8));
        new ComponentMaintenance(files).uninstall("glibc_box64_0.4.4");
        assertFalse(selected.exists()); assertTrue(other.isFile()); assertFalse(receipt.exists());
    }
    @Test public void uninstallSelectedBox64KeepsOtherVersionsAndPrefix() throws Exception {
        for (String id : new String[]{"glibc_box64_0.3.8", "rootfs_box64_0.2.8", "rootfs_box64_0.3.8"}) {
            File files = temp.newFolder();
            File install = new ComponentStoragePaths(files).getInstallDirectory();
            File selected = new File(install, id + "/versions/one/bin/box64");
            File other = new File(install, "rootfs_box64_0.4.5/versions/old/bin/box64");
            File nativeSelected = new File(files, "usr/glibc/components/" + id + "/bin/box64");
            File nativeOther = new File(files, "usr/glibc/components/glibc_box64_0.4.4/bin/box64");
            File prefix = new File(files, "front/wine/wine-one/drive_c/save.dat");
            for (File f : new File[]{selected, other, nativeOther, prefix}) write(f);
            if (id.startsWith("glibc_")) write(nativeSelected);
            new ComponentMaintenance(files).uninstall(id);
            assertFalse(selected.exists()); assertFalse(nativeSelected.exists());
            assertTrue(other.isFile()); assertTrue(nativeOther.isFile()); assertTrue(prefix.isFile());
        }
    }

    @Test public void unlinksSelectedSymlinkWithoutTraversingTarget() throws Exception {
        File files = temp.newFolder();
        File other = new File(files, "keep/bin/wine"); write(other);
        File link = new File(files, "usr/glibc/glibc_wine_9.3-vanilla"); link.getParentFile().mkdirs();
        Files.createSymbolicLink(link.toPath(), other.getParentFile().getParentFile().toPath());
        new ComponentMaintenance(files).uninstall(link.getName());
        assertFalse(Files.isSymbolicLink(link.toPath())); assertTrue(other.isFile());
    }
    @Test public void rejectsSymlinkParentsIncludingLinksInsideFilesDirectory() throws Exception {
        File files = temp.newFolder();
        File victim = new File(files, "keep/glibc_wine_9.3-vanilla/bin/wine"); write(victim);
        new File(files, "usr").mkdirs();
        Files.createSymbolicLink(new File(files, "usr/glibc").toPath(), new File(files, "keep").toPath());
        try { new ComponentMaintenance(files).uninstall("glibc_wine_9.3-vanilla"); fail(); }
        catch (IOException expected) { assertEquals("component_path_escape", expected.getMessage()); }
        assertTrue(victim.isFile());
    }
    @Test public void explicitDownloadCleanupOnlyRemovesChosenComponentArchives() throws Exception {
        File files = temp.newFolder(), cache = new File(files, "download");
        File selected = new File(cache, "native-vkd3d-1.archive");
        File partial = new File(cache, "native-vkd3d-1.part");
        File other = new File(cache, "native-dxvk-dev-1.archive");
        File unrelated = new File(cache, "native-vkd3d-notes.txt");
        for (File file : new File[]{selected, partial, other, unrelated}) write(file);
        new ComponentMaintenance(files).clearDownload("native-vkd3d", cache);
        assertFalse(selected.exists()); assertFalse(partial.exists());
        assertTrue(other.exists()); assertTrue(unrelated.exists());
    }
}
