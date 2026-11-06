package com.termux.ui.wine.recovery;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.apache.commons.compress.archivers.zip.*;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

public class WineCDriveArchiveTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final TestFs fs = new TestFs();
    private final WineCDriveArchive engine = new WineCDriveArchive(fs);
    private File prefix, archive, external;
    @Before public void setup() throws Exception {
        prefix = temporary.newFolder("prefix"); archive = new File(temporary.getRoot(), "test.wine-cdrive.zip");
        external = temporary.newFile("outside.txt"); write(external, "EXTERNAL_NOT_ARCHIVED");
        new File(prefix, "drive_c/Program Files").mkdirs();
        write(new File(prefix, "drive_c/Program Files/program.txt"), "BACKED_UP_DATA");
        for (String reg : Arrays.asList("system.reg", "user.reg", "userdef.reg")) write(new File(prefix, reg), "registry-" + reg);
        new File(prefix, ".games-runtime").mkdir(); write(new File(prefix, ".games-runtime/ready"), "CURRENT_RUNTIME");
        new File(prefix, "dosdevices").mkdir(); Files.createSymbolicLink(new File(prefix, "dosdevices/c:").toPath(), Paths.get("../drive_c"));
        Files.createSymbolicLink(new File(prefix, "drive_c/external-link").toPath(), external.toPath());
    }
    @Test public void roundTripIncludesOnlyCDriveAndRegistriesAndPreservesCurrentMountsAndMetadata() throws Exception {
        backup();
        try (ZipFile zip = new ZipFile(archive)) {
            Set<String> names = new HashSet<>(); Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) names.add(entries.nextElement().getName());
            assertFalse(names.stream().anyMatch(n -> n.startsWith("dosdevices") || n.startsWith(".games-runtime")));
            assertTrue(zip.getEntry("drive_c/external-link").isUnixSymlink());
            assertTrue(names.containsAll(Arrays.asList("system.reg", "user.reg", "userdef.reg")));
        }
        write(new File(prefix, "drive_c/Program Files/program.txt"), "CHANGED");
        write(new File(prefix, "system.reg"), "CHANGED_REGISTRY");
        write(new File(prefix, ".games-runtime/ready"), "KEEP_NEW_METADATA");
        engine.restore(prefix, archive, "wine_10.0", "restore", message -> { });
        assertEquals("BACKED_UP_DATA", read(new File(prefix, "drive_c/Program Files/program.txt")));
        assertEquals("registry-system.reg", read(new File(prefix, "system.reg")));
        assertEquals("KEEP_NEW_METADATA", read(new File(prefix, ".games-runtime/ready")));
        assertEquals(Paths.get("../drive_c"), Files.readSymbolicLink(new File(prefix, "dosdevices/c:").toPath()));
        assertEquals(external.toPath(), Files.readSymbolicLink(new File(prefix, "drive_c/external-link").toPath()));
        assertEquals("EXTERNAL_NOT_ARCHIVED", read(external));
    }
    @Test public void wineMismatchDoesNotChangeOriginalPrefix() throws Exception {
        backup(); assertFails("cdrive_wine_mismatch", () -> engine.restore(prefix, archive, "wine_other", "restore", m -> { }));
        assertEquals("BACKED_UP_DATA", read(new File(prefix, "drive_c/Program Files/program.txt")));
    }
    @Test public void rejectsPathsOutsideScopeAndTraversalWithoutPublication() throws Exception {
        for (String name : Arrays.asList("../escape", "drive_c/../../escape", "/drive_c/escape", "dosdevices/c:", "drive_c//bad")) {
            malicious(name);
            assertThrows(IOException.class, () -> engine.restore(prefix, archive, "wine_10.0", "restore", m -> { }));
            assertEquals("BACKED_UP_DATA", read(new File(prefix, "drive_c/Program Files/program.txt")));
            assertFalse(new File(temporary.getRoot(), "escape").exists());
        }
    }
    @Test public void rejectsCorruptedAndTruncatedArchivesWithoutChangingOriginal() throws Exception {
        malicious("drive_c/extra");
        byte[] bytes = Files.readAllBytes(archive.toPath());
        byte[] marker = "registry-system.reg".getBytes(StandardCharsets.UTF_8);
        int offset = index(bytes, marker); assertTrue(offset > 0); bytes[offset] ^= 1;
        Files.write(archive.toPath(), bytes);
        assertThrows(IOException.class, () -> engine.restore(prefix, archive, "wine_10.0", "restore", m -> { }));
        malicious("drive_c/extra"); bytes = Files.readAllBytes(archive.toPath());
        Files.write(archive.toPath(), Arrays.copyOf(bytes, bytes.length - 22));
        assertThrows(IOException.class, () -> engine.restore(prefix, archive, "wine_10.0", "restore", m -> { }));
        assertEquals("registry-system.reg", read(new File(prefix, "system.reg")));
    }
    @Test public void publicationFailureKeepsOriginalData() throws Exception {
        backup(); write(new File(prefix, "system.reg"), "ORIGINAL_AT_RESTORE"); fs.rejectStage = true;
        assertThrows(IOException.class, () -> engine.restore(prefix, archive, "wine_10.0", "restore", m -> { }));
        assertEquals("ORIGINAL_AT_RESTORE", read(new File(prefix, "system.reg")));
        assertFalse(WineCDriveArchive.old(prefix, "restore").exists());
    }
    @Test public void interruptedPublicationRecoversOriginalAndDoesNotReplayArchive() throws Exception {
        File old = WineCDriveArchive.old(prefix, "restore"); fs.move(prefix, old);
        WineCDriveArchive.stage(prefix, "restore").mkdir();
        engine.recover(prefix, archive, "restore", true, false, "");
        assertEquals("registry-system.reg", read(new File(prefix, "system.reg")));
        assertFalse(WineCDriveArchive.stage(prefix, "restore").exists());
    }
    @Test public void committedRecoveryKeepsPublishedDataAndOnlyCleansOldTree() throws Exception {
        File old = WineCDriveArchive.old(prefix, "restore"); old.mkdir(); write(new File(old, "old"), "old");
        engine.recover(prefix, archive, "restore", true, true, "");
        assertFalse(old.exists()); assertEquals("registry-system.reg", read(new File(prefix, "system.reg")));
    }
    @Test public void existingBackupIsNeverOverwrittenAndMissingRegistryFails() throws Exception {
        write(archive, "KEEP_ARCHIVE"); assertFails("cdrive_destination_exists", this::backup);
        assertEquals("KEEP_ARCHIVE", read(archive)); archive.delete();
        new File(prefix, "user.reg").delete(); assertFails("cdrive_registry_missing", this::backup);
        assertFalse(archive.exists());
    }
    @Test public void failedExportRemovesOnlyTheExclusivelyCreatedOutput() throws Exception {
        assertThrows(IllegalStateException.class, () -> engine.backup(prefix, archive, "wine_10.0", "backup", message -> {
            if (message.startsWith("Backing up ")) throw new IllegalStateException("interrupted export");
        }));
        assertFalse(archive.exists());
        assertEquals("registry-system.reg", read(new File(prefix, "system.reg")));
    }
    @Test public void recoveryRequiresTheRecordedFileIdentity() throws Exception {
        backup(); String identity = fs.identity(archive);
        assertFails("cdrive_output_changed", () -> engine.recover(prefix, archive, "backup", false, false, "foreign-identity"));
        assertTrue(archive.exists());
        engine.recover(prefix, archive, "backup", false, true, identity);
        assertTrue(archive.exists());
        engine.recover(prefix, archive, "backup", false, false, identity);
        assertFalse(archive.exists());
    }
    @Test public void unsupportedSourceNamesFailBeforeProducingAnUnrestorableBackup() throws Exception {
        write(new File(prefix, "drive_c/bad\\name"), "invalid");
        assertFails("cdrive_invalid_path", this::backup);
        assertFalse(archive.exists());
    }
    @Test public void restoreRepairsMissingRegistryAndKeepsFileTimestamp() throws Exception {
        File program = new File(prefix, "drive_c/Program Files/program.txt");
        long modified = 1234567890000L; assertTrue(program.setLastModified(modified));
        backup(); new File(prefix, "user.reg").delete(); write(program, "changed");
        engine.restore(prefix, archive, "wine_10.0", "restore", m -> { });
        assertEquals("registry-user.reg", read(new File(prefix, "user.reg")));
        assertTrue(Math.abs(program.lastModified() - modified) <= 2000);
    }
    @Test public void explicitRestoreReestablishesLostIdentityWithoutRebuildingCDrive() throws Exception {
        backup();
        engine.restore(prefix, archive, "wine_10.0", "restore", m -> { });
        assertEquals("restored|wine_10.0\n", read(new File(prefix, ".games-runtime/runtime")));
        assertEquals("BACKED_UP_DATA", read(new File(prefix, "drive_c/Program Files/program.txt")));
    }
    @Test public void explicitRestorePreservesExistingRuntimeIdentity() throws Exception {
        write(new File(prefix, ".games-runtime/runtime"), "current-root|wine_10.0");
        backup();
        engine.restore(prefix, archive, "wine_10.0", "restore", m -> { });
        assertEquals("current-root|wine_10.0", read(new File(prefix, ".games-runtime/runtime")));
    }
    @Test public void explicitRestoreRepairsEmptyIdentityInUnpublishedStage() throws Exception {
        write(new File(prefix, ".games-runtime/runtime"), "");
        backup();
        engine.restore(prefix, archive, "wine_10.0", "restore", m -> { });
        assertEquals("restored|wine_10.0\n", read(new File(prefix, ".games-runtime/runtime")));
        assertEquals("BACKED_UP_DATA", read(new File(prefix, "drive_c/Program Files/program.txt")));
    }
    private void backup() throws Exception { engine.backup(prefix, archive, "wine_10.0", "backup", m -> { }); }
    private void malicious(String extra) throws Exception {
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            zip.setMethod(ZipArchiveOutputStream.STORED);
            Properties properties = new Properties(); properties.setProperty("format", "wine-cdrive-1");
            properties.setProperty("backend", "rootfs_proot"); properties.setProperty("winePackage", "wine_10.0");
            ByteArrayOutputStream manifest = new ByteArrayOutputStream(); properties.store(manifest, null);
            entry(zip, "cdrive.properties", manifest.toByteArray(), false);
            entry(zip, "drive_c/", new byte[0], true);
            for (String reg : Arrays.asList("system.reg", "user.reg", "userdef.reg")) entry(zip, reg, ("registry-" + reg).getBytes(StandardCharsets.UTF_8), false);
            entry(zip, extra, "payload".getBytes(StandardCharsets.UTF_8), false);
        }
    }
    private static void entry(ZipArchiveOutputStream zip, String name, byte[] data, boolean dir) throws Exception {
        ZipArchiveEntry entry = new ZipArchiveEntry(name); entry.setUnixMode(dir ? 040700 : 0100600);
        entry.setSize(data.length); java.util.zip.CRC32 crc = new java.util.zip.CRC32(); crc.update(data); entry.setCrc(crc.getValue());
        zip.putArchiveEntry(entry); zip.write(data); zip.closeArchiveEntry();
    }
    private static int index(byte[] data, byte[] pattern) {
        outer: for (int i = 0; i <= data.length - pattern.length; i++) { for (int j = 0; j < pattern.length; j++) if (data[i+j] != pattern[j]) continue outer; return i; } return -1;
    }
    private interface Io { void run() throws Exception; }
    private static void assertFails(String code, Io action) { assertEquals(code, assertThrows(IOException.class, action::run).getMessage()); }
    private static void write(File file, String value) throws IOException { Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8)); }
    private static String read(File file) throws IOException { return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8); }
    private static class TestFs implements WineCDriveArchive.FileSystem {
        boolean rejectStage;
        public int kind(File f) throws IOException { return Files.isSymbolicLink(f.toPath()) ? 3 : Files.isDirectory(f.toPath(), LinkOption.NOFOLLOW_LINKS) ? 2 : Files.isRegularFile(f.toPath(), LinkOption.NOFOLLOW_LINKS) ? 1 : 0; }
        public int mode(File f) throws IOException { return kind(f) == 2 ? 0700 : 0600; }
        public InputStream read(File f) throws IOException { return Files.newInputStream(f.toPath(), LinkOption.NOFOLLOW_LINKS); }
        public WineCDriveArchive.OwnedOutput create(File f) throws IOException {
            OutputStream output = Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE_NEW);
            String id = identity(f);
            return new WineCDriveArchive.OwnedOutput() {
                public String identity() { return id; }
                public void write(int b) throws IOException { output.write(b); }
                public void write(byte[] b, int o, int n) throws IOException { output.write(b,o,n); }
                public void close() throws IOException { output.close(); }
            };
        }
        public String identity(File f) throws IOException { return Files.readAttributes(f.toPath(), "unix:dev,ino", LinkOption.NOFOLLOW_LINKS).toString(); }
        public String readLink(File f) throws IOException { return Files.readSymbolicLink(f.toPath()).toString(); }
        public void link(String t, File f) throws IOException { Files.createSymbolicLink(f.toPath(), Paths.get(t)); }
        public void chmod(File f, int mode) { }
        public void move(File a, File b) throws IOException { if (rejectStage && a.getName().endsWith("-stage")) throw new IOException("injected_publish_failure"); Files.move(a.toPath(), b.toPath()); }
    }
}
