package com.termux.ui.files;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class BrowserFileOperationsTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private File root, games, destination;
    private BrowserFileOperations operations;
    private final BrowserFileOperations.Links links = new BrowserFileOperations.Links() {
        public boolean isLink(File file) { return Files.isSymbolicLink(file.toPath()); }
        public String read(File file) throws IOException { return Files.readSymbolicLink(file.toPath()).toString(); }
        public void create(String target, File file) throws IOException { Files.createSymbolicLink(file.toPath(), new File(target).toPath()); }
    };
    @Before public void setup() throws Exception {
        root = temp.newFolder("private"); games = new File(root, "front/games"); assertTrue(games.mkdirs());
        destination = new File(root, "destination"); assertTrue(destination.mkdir());
        operations = new BrowserFileOperations(links, root, games);
    }
    private File file(String name, String text) throws IOException {
        File result = new File(root, name); result.getParentFile().mkdirs();
        Files.write(result.toPath(), text.getBytes("UTF-8")); return result;
    }
    private void fails(String reason, Throwing action) throws Exception {
        try { action.run(); fail("Expected " + reason); }
        catch (IOException e) { assertTrue(e.toString(), e.getMessage().startsWith(reason)); }
    }
    private interface Throwing { void run() throws Exception; }

    @Test public void createsEmptyFilesAndSingleFoldersInEveryRootWithoutChangingNames() throws Exception {
        File shared = temp.newFolder("shared");
        BrowserFileOperations creator = new BrowserFileOperations(links, root, games, shared);
        for (File parent : new File[]{root, games, shared}) {
            File result = creator.create(parent, "文件 no-extension ", false);
            assertEquals(new File(parent, "文件 no-extension "), result);
            assertTrue(result.isFile()); assertEquals(0, result.length());
            File directory = creator.create(parent, ".folder", true);
            assertTrue(directory.isDirectory()); assertEquals(0, directory.list().length);
        }
    }

    @Test public void createRejectsInvalidNamesAndLeavesDirectoryUntouched() throws Exception {
        for (String name : new String[]{null, "", " \t\n", ".", "..", "../escape", "/absolute", "a/b", "a\\b", "a\0b"}) {
            fails("invalid_name", () -> operations.create(destination, name, false));
            fails("invalid_name", () -> operations.create(destination, name, true));
        }
        assertEquals(0, destination.list().length);
    }

    @Test public void createNeverOverwritesFilesDirectoriesOrDanglingLinks() throws Exception {
        File existing = file("destination/existing", "precious");
        File child = file("destination/folder/child", "child");
        File link = new File(destination, "link");
        Path missing = new File(root, "missing").toPath();
        Files.createSymbolicLink(link.toPath(), missing);
        for (String name : new String[]{"existing", "folder", "link"}) {
            fails("destination_exists", () -> operations.create(destination, name, false));
            fails("destination_exists", () -> operations.create(destination, name, true));
        }
        assertEquals("precious", new String(Files.readAllBytes(existing.toPath()), "UTF-8"));
        assertTrue(child.isFile()); assertEquals(missing, Files.readSymbolicLink(link.toPath()));
        assertFalse(Files.exists(missing));
    }

    @Test public void createRejectsOutsideMissingAndNonDirectoryParents() throws Exception {
        File outside = temp.newFolder("private-other");
        File alias = new File(root, "escape"); Files.createSymbolicLink(alias.toPath(), outside.toPath());
        for (File parent : new File[]{outside, alias}) {
            fails("outside_roots", () -> operations.create(parent, "new", false));
            fails("outside_roots", () -> operations.create(parent, "new", true));
        }
        for (File parent : new File[]{new File(root, "absent"), file("regular", "one")}) {
            fails("destination_missing", () -> operations.create(parent, "new", false));
            fails("destination_missing", () -> operations.create(parent, "new", true));
        }
        assertEquals(0, outside.list().length); assertFalse(new File(root, "absent").exists());
    }

    @Test public void interruptedCreateDoesNotWriteAnything() throws Exception {
        Thread.currentThread().interrupt();
        try { fails("operation_interrupted", () -> operations.create(destination, "new", false)); }
        finally { Thread.interrupted(); }
        assertEquals(0, destination.list().length);
    }

    @Test public void copyIncludesHiddenChildrenAndPreservesSource() throws Exception {
        File source = file("folder/.hidden", "hidden").getParentFile(); file("folder/sub/a.txt", "payload");
        File result = operations.paste(source, destination, false);
        assertEquals("hidden", new String(Files.readAllBytes(new File(result, ".hidden").toPath()), "UTF-8"));
        assertEquals("payload", new String(Files.readAllBytes(new File(result, "sub/a.txt").toPath()), "UTF-8"));
        assertTrue(new File(source, ".hidden").isFile());
    }
    @Test public void cutRemovesSourceOnlyAfterPasting() throws Exception {
        File source = file("source.txt", "one"); assertTrue(source.exists());
        File result = operations.paste(source, destination, true);
        assertFalse(source.exists()); assertEquals("one", new String(Files.readAllBytes(result.toPath()), "UTF-8"));
    }
    @Test public void conflictNeverOverwritesOrDeletesCutSource() throws Exception {
        File source = file("one.txt", "new"); File target = file("destination/one.txt", "old");
        fails("destination_exists", () -> operations.paste(source, destination, true));
        assertTrue(source.exists()); assertEquals("old", new String(Files.readAllBytes(target.toPath()), "UTF-8"));
    }
    @Test public void cannotPasteIntoSelfOrChild() throws Exception {
        File source = file("tree/child/file", "one").getParentFile().getParentFile();
        fails("destination_inside_source", () -> operations.paste(source, source, false));
        fails("destination_inside_source", () -> operations.paste(source, new File(source, "child"), true));
        fails("destination_inside_source", () -> operations.paste(new File(source, "child/file"), new File(source, "child"), true));
    }
    @Test public void protectsRootsAliasesAndAncestorsOfNestedRoot() throws Exception {
        for (File source : new File[] {root, games, games.getParentFile()}) {
            assertFalse(operations.canOperate(source));
            fails("protected_root", () -> operations.delete(source));
            fails("protected_root", () -> operations.paste(source, destination, false));
        }
        File alias = new File(root, "alias"); Files.createSymbolicLink(alias.toPath(), games.toPath());
        assertFalse(operations.canOperate(alias)); fails("protected_root", () -> operations.delete(alias));
        assertTrue(games.isDirectory());
    }
    @Test public void rootsAreValidPasteTargetsAndPrefixNeighboursAreNot() throws Exception {
        File source = file("folder/item", "one");
        assertTrue(operations.paste(source, root, false).isFile());
        assertTrue(operations.paste(source, games, false).isFile());
        File neighbour = temp.newFolder("private-other");
        fails("outside_roots", () -> operations.paste(source, neighbour, false));
    }
    @Test public void sourceOutsideScopeAndMissingSourceFail() throws Exception {
        File outside = temp.newFile("outside");
        fails("outside_roots", () -> operations.delete(outside));
        fails("source_missing", () -> operations.paste(new File(root, "missing"), destination, false));
    }
    @Test public void copyAndDeleteNeverTraverseDirectoryLinks() throws Exception {
        File outside = temp.newFolder("outside"); Path precious = new File(outside, "precious").toPath(); Files.write(precious, new byte[] {1});
        File link = new File(root, "link"); Files.createSymbolicLink(link.toPath(), outside.toPath());
        File result = operations.paste(link, destination, false);
        assertTrue(Files.isSymbolicLink(result.toPath())); assertEquals(outside.toPath(), Files.readSymbolicLink(result.toPath()));
        operations.delete(link); assertFalse(Files.exists(link.toPath())); assertTrue(Files.exists(precious));
    }
    @Test public void danglingTargetLinkIsAConflict() throws Exception {
        File source = file("item", "one"); Files.createSymbolicLink(new File(destination, "item").toPath(), new File(root, "missing").toPath());
        fails("destination_exists", () -> operations.paste(source, destination, true)); assertTrue(source.exists());
    }
    @Test public void linkCreationFailureCleansStagingAndKeepsCutSource() throws Exception {
        file("tree/a", "one"); Files.createSymbolicLink(new File(root, "tree/link").toPath(), new File(root, "tree/a").toPath());
        BrowserFileOperations broken = new BrowserFileOperations(new BrowserFileOperations.Links() {
            public boolean isLink(File file) throws IOException { return links.isLink(file); }
            public String read(File file) throws IOException { return links.read(file); }
            public void create(String target, File file) throws IOException { throw new IOException("links_unsupported"); }
        }, root, games);
        fails("links_unsupported", () -> broken.paste(new File(root, "tree"), destination, true));
        assertTrue(new File(root, "tree/a").exists()); assertEquals(0, destination.list().length);
    }
    @Test public void interruptionDoesNotDeleteSource() throws Exception {
        File source = file("source", "one"); Thread.currentThread().interrupt();
        try { fails("operation_interrupted", () -> operations.paste(source, destination, true)); }
        finally { Thread.interrupted(); }
        assertTrue(source.exists()); assertEquals(0, destination.list().length);
    }
    @Test public void deleteRecursivelyIncludesHiddenFiles() throws Exception {
        File source = file("tree/.hidden/sub/item", "one");
        operations.delete(new File(root, "tree")); assertFalse(source.exists()); assertFalse(new File(root, "tree").exists());
    }
    @Test public void cutSourceDeleteFailureRetainsPublishedDestinationAndReportsFailure() throws Exception {
        File source = file("source", "payload"); File target = new File(destination, "source");
        BrowserFileOperations broken = new BrowserFileOperations(new BrowserFileOperations.Links() {
            public boolean isLink(File file) throws IOException {
                if (file.equals(source) && target.exists()) throw new IOException("delete_permission_denied");
                return links.isLink(file);
            }
            public String read(File file) throws IOException { return links.read(file); }
            public void create(String value, File file) throws IOException { links.create(value, file); }
        }, root, games);
        fails("move_source_delete_failed", () -> broken.paste(source, destination, true));
        assertEquals("payload", new String(Files.readAllBytes(target.toPath()), "UTF-8"));
        assertTrue(source.isFile()); assertEquals(1, destination.list().length);
    }
    @Test public void interruptionDuringCopyCleansStagingAndPreservesCutSource() throws Exception {
        File child = file("tree/child", "payload"); File source = child.getParentFile();
        BrowserFileOperations interrupted = new BrowserFileOperations(new BrowserFileOperations.Links() {
            public boolean isLink(File file) throws IOException {
                if (file.equals(child)) Thread.currentThread().interrupt();
                return links.isLink(file);
            }
            public String read(File file) throws IOException { return links.read(file); }
            public void create(String value, File file) throws IOException { links.create(value, file); }
        }, root, games);
        try {
            fails("operation_interrupted", () -> interrupted.paste(source, destination, true));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertTrue(child.isFile()); assertEquals(0, destination.list().length);
    }

}
