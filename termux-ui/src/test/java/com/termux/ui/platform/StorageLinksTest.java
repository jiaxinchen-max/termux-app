package com.termux.ui.platform;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.Assert.*;

public class StorageLinksTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final StorageLinks.FileSystem fs = new StorageLinks.FileSystem() {
        public StorageLinks.Kind kind(File file) {
            Path path = file.toPath();
            if (Files.isSymbolicLink(path)) return StorageLinks.Kind.LINK;
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return StorageLinks.Kind.MISSING;
            return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? StorageLinks.Kind.DIRECTORY : StorageLinks.Kind.OTHER;
        }
        public String readLink(File file) throws IOException { return Files.readSymbolicLink(file.toPath()).toString(); }
        public void mkdirs(File file) throws IOException { Files.createDirectories(file.toPath()); }
        public void unlink(File file) throws IOException { Files.delete(file.toPath()); }
        public void symlink(String target, File file) throws IOException { Files.createSymbolicLink(file.toPath(), new File(target).toPath()); }
    };
    private Map<String, File> targets(File shared) {
        Map<String, File> result = new LinkedHashMap<>();
        result.put("shared", shared);
        result.put("downloads", new File(shared, "Download"));
        return result;
    }
    @Test public void initializesMissingLinksAndRepeatedSetupPreservesCustomFiles() throws Exception {
        File shared = temporary.newFolder("external"), storage = new File(temporary.getRoot(), "home/storage");
        StorageLinks.ensure(storage, targets(shared), fs);
        Path custom = storage.toPath().resolve("my-file");
        Files.write(custom, new byte[] {42});
        Object linkIdentity = Files.getAttribute(storage.toPath().resolve("shared"), "unix:ino", LinkOption.NOFOLLOW_LINKS);
        StorageLinks.ensure(storage, targets(shared), fs);
        assertEquals(shared.toPath(), Files.readSymbolicLink(storage.toPath().resolve("shared")));
        assertArrayEquals(new byte[] {42}, Files.readAllBytes(custom));
        assertEquals(linkIdentity, Files.getAttribute(storage.toPath().resolve("shared"), "unix:ino", LinkOption.NOFOLLOW_LINKS));
    }
    @Test public void repairsDanglingAndWrongStandardLinksWithoutChangingTheirTargets() throws Exception {
        File shared = temporary.newFolder("external"), storage = temporary.newFolder("storage");
        Path old = temporary.newFolder("old").toPath();
        Files.write(old.resolve("keep"), new byte[] {7});
        Files.createSymbolicLink(storage.toPath().resolve("shared"), old);
        Files.createSymbolicLink(storage.toPath().resolve("downloads"), old.resolve("missing"));
        StorageLinks.ensure(storage, targets(shared), fs);
        assertEquals(shared.toPath(), Files.readSymbolicLink(storage.toPath().resolve("shared")));
        assertEquals(new File(shared, "Download").toPath(), Files.readSymbolicLink(storage.toPath().resolve("downloads")));
        assertArrayEquals(new byte[] {7}, Files.readAllBytes(old.resolve("keep")));
    }
    @Test public void standardNameCollisionPreservesFileAndDoesNotPartiallyCreateLinks() throws Exception {
        File shared = temporary.newFolder("external"), storage = temporary.newFolder("storage");
        Path collision = storage.toPath().resolve("downloads");
        Files.write(collision, new byte[] {9});
        try { StorageLinks.ensure(storage, targets(shared), fs); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().startsWith("storage_link_conflict")); }
        assertArrayEquals(new byte[] {9}, Files.readAllBytes(collision));
        assertFalse(Files.exists(storage.toPath().resolve("shared"), LinkOption.NOFOLLOW_LINKS));
    }
    @Test public void preservesSameNameDirectoryAndItsContents() throws Exception {
        File shared = temporary.newFolder("external"), storage = temporary.newFolder("storage");
        Path user = Files.createDirectory(storage.toPath().resolve("shared"));
        Files.write(user.resolve("keep"), new byte[] {8});
        try { StorageLinks.ensure(storage, targets(shared), fs); fail(); } catch (IOException expected) { }
        assertArrayEquals(new byte[] {8}, Files.readAllBytes(user.resolve("keep")));
    }
    @Test public void refusesStorageDirectorySymlinkRatherThanWritingOutsideHome() throws Exception {
        File outside = temporary.newFolder("outside"), storage = new File(temporary.getRoot(), "storage");
        Files.createSymbolicLink(storage.toPath(), outside.toPath());
        try { StorageLinks.ensure(storage, targets(outside), fs); fail(); } catch (IOException expected) { }
        assertEquals(0, outside.list().length);
    }
}
