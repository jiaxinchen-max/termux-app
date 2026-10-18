package com.termux.localgames.runtime;

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
        assertEquals("a.tar", RuntimeBackupSharedStorage.normalizeArchiveName(" a "));
        assertEquals("a.tar", RuntimeBackupSharedStorage.normalizeArchiveName("a.tar"));
        assertEquals("a (1)+b=c,d.tar", RuntimeBackupSharedStorage.normalizeArchiveName("a (1)+b=c,d"));
        assertEquals("b.tar.zst", RuntimeBackupSharedStorage.normalizeArchiveName("b", ".tar.zst"));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName(""));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName(".."));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName("a/b"));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName("备份"));
        assertNull(RuntimeBackupSharedStorage.normalizeArchiveName(".tar"));
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
        assertTrue(new File(root, "b.tar").createNewFile());
        assertTrue(new File(root, "a.TAR").createNewFile());
        assertTrue(new File(root, "c.txt").createNewFile());
        assertTrue(new File(root, "d.tar.zst").createNewFile());
        assertEquals("[alpha, Zeta, a.TAR, b.tar]", names(
            RuntimeBackupSharedStorage.listChoices(root, root, false)));
        assertEquals("[alpha, Zeta]", names(RuntimeBackupSharedStorage.listChoices(root, root, true)));
        assertEquals("[alpha, Zeta, d.tar.zst]", names(RuntimeBackupSharedStorage.listChoices(
            root, root, false, RuntimeBackupSharedStorage.ROOTFS_ARCHIVE_EXTENSION)));
        assertTrue(RuntimeBackupSharedStorage.listChoices(root, temp.newFolder("other"), false).isEmpty());
    }

    @Test
    public void exportNamesAreEnglishAndTimestamped() {
        Date now = new Date(0L);
        assertTrue(RuntimeBackupSharedStorage.exportFileName(RuntimeBackupType.GLIBC, now)
            .matches("termux-games-glibc-\\d{8}-\\d{6}\\.tar"));
        assertTrue(RuntimeBackupSharedStorage.rootfsExportFileName(now)
            .matches("games-base-backup-\\d{8}-\\d{6}\\.tar\\.zst"));
    }

    @Test
    public void exportEstimateCoversOnlyUsrGlibc() throws IOException {
        File files = temp.newFolder("files");
        File glibc = new File(files, "usr/glibc");
        assertTrue(glibc.mkdirs());
        write(new File(glibc, "f"), 700);
        File other = new File(files, "usr/other");
        assertTrue(other.mkdirs());
        write(new File(other, "big"), 100000);
        // 2 x (header + block) for the manifest and end marker, glibc dir, file header + 2 blocks.
        assertEquals(2048L + 512L + 512L + 1024L, RuntimeBackupSharedStorage.estimateExportBytes(files));
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
    public void spaceChecksReportRequiredAndAvailable() throws IOException {
        long mb = 1024L * 1024L;
        RuntimeBackupSharedStorage.requireExportSpace(10 * mb, 74 * mb);
        try {
            RuntimeBackupSharedStorage.requireExportSpace(10 * mb, 73 * mb);
            fail();
        } catch (RuntimeBackupSharedStorage.InsufficientSpaceException error) {
            assertEquals(74 * mb, error.getRequiredBytes());
            assertEquals(73 * mb, error.getAvailableBytes());
        }
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
