package com.termux.ui.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Date;
import java.util.List;

public final class RuntimeBackupSharedStorageTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void normalizesNamesToEnglishArchiveNames() {
        String extension = RuntimeBackupSharedStorage.ROOTFS_ARCHIVE_EXTENSION;
        assertEquals("a.tar.zst", RuntimeBackupSharedStorage.normalizeArchiveName(" a ", extension));
        assertEquals("a.tar.zst", RuntimeBackupSharedStorage.normalizeArchiveName("a.tar.zst", extension));
        assertEquals("a (1)+b=c,d.tar.zst",
            RuntimeBackupSharedStorage.normalizeArchiveName("a (1)+b=c,d", extension));
        assertEquals("b.desktop.tar.gz",
            RuntimeBackupSharedStorage.normalizeArchiveName("b", ".desktop.tar.gz"));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName("", extension));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName("..", extension));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName("a/b", extension));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName("备份", extension));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName(".tar.zst", extension));
    }

    @Test
    public void containmentFollowsCanonicalPaths() throws IOException {
        File root = temp.newFolder("root");
        File inside = new File(root, "a/b");
        assertTrue(inside.mkdirs());
        File outside = temp.newFolder("outside");
        assertTrue(RuntimeBackupSharedStorage.isWithin(root, inside));
        assertTrue(RuntimeBackupSharedStorage.isWithin(root, root));
        assertFalse(RuntimeBackupSharedStorage.isWithin(root, outside));
        assertFalse(RuntimeBackupSharedStorage.isWithin(root, new File(root, "../outside")));
        assertEquals(new File(root, "a"), RuntimeBackupSharedStorage.parentWithin(root, inside));
        assertNull(RuntimeBackupSharedStorage.parentWithin(root, root));
    }

    @Test
    public void listsFoldersThenMatchingArchivesOnly() throws IOException {
        File root = temp.newFolder("root");
        assertTrue(new File(root, "Zeta").mkdir());
        assertTrue(new File(root, "alpha").mkdir());
        assertTrue(new File(root, ".hidden").mkdir());
        assertTrue(new File(root, "b.tar.zst").createNewFile());
        assertTrue(new File(root, "a.TAR.ZST").createNewFile());
        assertTrue(new File(root, "c.txt").createNewFile());
        assertTrue(new File(root, "d.desktop.tar.gz").createNewFile());
        assertEquals("[alpha, Zeta, a.TAR.ZST, b.tar.zst]", names(
            RuntimeBackupSharedStorage.listChoices(root, root, false,
                RuntimeBackupSharedStorage.ROOTFS_ARCHIVE_EXTENSION)));
        assertEquals("[alpha, Zeta]", names(RuntimeBackupSharedStorage.listChoices(
            root, root, true, RuntimeBackupSharedStorage.ROOTFS_ARCHIVE_EXTENSION)));
        assertEquals("[alpha, Zeta, d.desktop.tar.gz]", names(RuntimeBackupSharedStorage.listChoices(
            root, root, false, ".desktop.tar.gz")));
        assertTrue(RuntimeBackupSharedStorage.listChoices(root, temp.newFolder("other"), false,
            RuntimeBackupSharedStorage.ROOTFS_ARCHIVE_EXTENSION).isEmpty());
    }

    @Test
    public void exportNamesAreEnglishAndTimestamped() {
        Date now = new Date(0L);
        assertTrue(RuntimeBackupSharedStorage.rootfsExportFileName(now)
            .matches("games-base-backup-\\d{8}-\\d{6}\\.tar\\.zst"));
    }

    @Test
    public void rootfsEstimateIgnoresCacheSubdirectories() throws IOException {
        File container = temp.newFolder("container");
        write(new File(container, "x"), 512);
        File cache = temp.newFolder("cache");
        write(new File(cache, "a.tar.zst"), 1);
        File staging = new File(cache, "backup-staging");
        assertTrue(staging.mkdir());
        write(new File(staging, "huge"), 100000);
        assertEquals(512L + 512L + 512L + 512L + 512L,
            RuntimeBackupSharedStorage.estimateRootfsBackupBytes(container, cache));
    }

    @Test
    public void rootfsEstimateCanLeaveThePrefixTemplatesOut() throws IOException {
        File container = temp.newFolder("container-templates");
        write(new File(container, "x"), 512);
        File cache = temp.newFolder("cache-templates");
        write(new File(cache, "games-rootfs-base-prefix-rootfs_hangover_11.16.tar.zst"), 100000);
        write(new File(cache, "games-rootfs-base.recipe"), 1);
        long with = RuntimeBackupSharedStorage.estimateRootfsBackupBytes(container, cache, true);
        long without = RuntimeBackupSharedStorage.estimateRootfsBackupBytes(container, cache, false);
        assertEquals(with, RuntimeBackupSharedStorage.estimateRootfsBackupBytes(container, cache));
        // The template archive is a header block plus its data rounded up to blocks; the recipe stays.
        assertEquals(512L + 100352L, with - without);
        assertTrue(without > 0);
    }

    @Test
    public void spaceChecksReportRequiredAndAvailable() throws IOException {
        long mb = 1024L * 1024L;
        long tree = 1000 * mb;
        // private: tree + tree/2 + 256 MB; destination: tree/2 + 256 MB
        RuntimeBackupSharedStorage.requireRootfsBackupSpace(tree, 1756 * mb, 756 * mb);
        try {
            RuntimeBackupSharedStorage.requireRootfsBackupSpace(tree, 1755 * mb, 756 * mb);
            fail();
        } catch (RuntimeBackupSharedStorage.InsufficientSpaceException expected) {
            assertEquals(1756 * mb, expected.getRequiredBytes());
        }
        try {
            RuntimeBackupSharedStorage.requireRootfsBackupSpace(tree, 5000 * mb, 755 * mb);
            fail();
        } catch (RuntimeBackupSharedStorage.InsufficientSpaceException expected) {
            assertEquals(756 * mb, expected.getRequiredBytes());
        }
    }

    private static String names(List<File> files) {
        StringBuilder out = new StringBuilder("[");
        for (File file : files) out.append(out.length() > 1 ? ", " : "").append(file.getName());
        return out.append(']').toString();
    }

    private static void write(File file, int length) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(new byte[length]); }
    }
}
