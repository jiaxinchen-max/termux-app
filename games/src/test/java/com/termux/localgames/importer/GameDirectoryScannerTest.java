package com.termux.localgames.importer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GameDirectoryScannerTest {

    @Test
    public void validatesPeAndRanksMainAboveLauncherAndHelpers() throws Exception {
        MemoryTree tree = new MemoryTree("Game Name")
            .file("Game Name.exe", 20L * 1024 * 1024, true)
            .file("Launcher.EXE", 1024, true)
            .file("unins000.exe", 1024, true)
            .file("fake.exe", 1024, false)
            .directory("_Redist")
            .file("_Redist/setup.exe", 1024, true);

        GameScanResult result = new GameDirectoryScanner(GameScanLimits.DEFAULT).scan(tree);

        assertEquals(4, result.getCandidates().size());
        assertEquals("Game Name.exe", result.getCandidates().get(0).getRelativePath());
        assertFalse(result.getCandidates().get(0).isDiscouraged());
        assertTrue(find(result, "unins000.exe").isDiscouraged());
        assertTrue(find(result, "_Redist/setup.exe").isDiscouraged());
        assertEquals(".", find(result, "Launcher.EXE").getWorkingDirectory());
        assertTrue(result.isComplete());
    }

    @Test
    public void reportsDepthDocumentDirectoryAndCandidateBounds() throws Exception {
        MemoryTree tree = new MemoryTree("Bounded")
            .file("a.exe", 1, true)
            .file("b.exe", 1, true)
            .directory("one")
            .file("one/c.exe", 1, true);

        GameScanResult candidateBound = new GameDirectoryScanner(
            new GameScanLimits(8, 20, 20, 1, 100)).scan(tree);
        assertEquals(1, candidateBound.getCandidates().size());
        assertTrue(candidateBound.getReachedLimits().contains(ScanLimit.CANDIDATES));

        GameScanResult depthBound = new GameDirectoryScanner(
            new GameScanLimits(0, 20, 20, 20, 100)).scan(tree);
        assertTrue(depthBound.getReachedLimits().contains(ScanLimit.DEPTH));

        GameScanResult documentBound = new GameDirectoryScanner(
            new GameScanLimits(8, 1, 20, 20, 100)).scan(tree);
        assertTrue(documentBound.getReachedLimits().contains(ScanLimit.DOCUMENTS));

        GameScanResult directoryBound = new GameDirectoryScanner(
            new GameScanLimits(8, 20, 1, 20, 100)).scan(tree);
        assertTrue(directoryBound.getReachedLimits().contains(ScanLimit.DIRECTORIES));
    }

    @Test
    public void totalSizeStopsScanWithoutOverflow() throws Exception {
        MemoryTree tree = new MemoryTree("Huge")
            .file("huge.bin", Long.MAX_VALUE, false)
            .file("later.exe", 1, true);

        GameScanResult result = new GameDirectoryScanner(
            new GameScanLimits(8, 20, 20, 20, 10)).scan(tree);

        assertTrue(result.getReachedLimits().contains(ScanLimit.TOTAL_BYTES));
        assertEquals(0, result.getCandidates().size());
    }

    @Test
    public void unreadableExecutableDoesNotHideReadableCandidate() throws Exception {
        MemoryTree tree = new MemoryTree("Readable")
            .unreadableFile("a.exe", 1)
            .file("game.exe", 1, true);

        GameScanResult result = new GameDirectoryScanner(GameScanLimits.DEFAULT).scan(tree);

        assertEquals(1, result.getUnreadableExecutables());
        assertEquals(1, result.getCandidates().size());
        assertEquals("game.exe", result.getCandidates().get(0).getRelativePath());
    }

    private static ExecutableCandidate find(GameScanResult result, String path) {
        for (ExecutableCandidate candidate : result.getCandidates()) {
            if (candidate.getRelativePath().equals(path)) return candidate;
        }
        throw new AssertionError("Missing candidate " + path);
    }

    private static final class MemoryTree implements GameDocumentTree {
        private final GameDocument root;
        private final Map<String, List<GameDocument>> children = new HashMap<>();
        private final Map<String, byte[]> contents = new HashMap<>();
        private final List<String> unreadable = new ArrayList<>();

        MemoryTree(String rootName) {
            root = new GameDocument("memory://root", rootName, true, -1);
            children.put(root.getUri(), new ArrayList<>());
        }

        MemoryTree directory(String path) {
            String parent = parent(path);
            GameDocument document = new GameDocument(uri(path), name(path), true, -1);
            children.get(uriForParent(parent)).add(document);
            children.put(document.getUri(), new ArrayList<>());
            return this;
        }

        MemoryTree file(String path, long size, boolean pe) {
            String parent = parent(path);
            GameDocument document = new GameDocument(uri(path), name(path), false, size);
            children.get(uriForParent(parent)).add(document);
            contents.put(document.getUri(), pe ? PeExecutableInspectorTest.peBytes() : new byte[256]);
            return this;
        }

        MemoryTree unreadableFile(String path, long size) {
            file(path, size, true);
            unreadable.add(uri(path));
            return this;
        }

        @Override public GameDocument root() { return root; }
        @Override public List<GameDocument> listChildren(GameDocument directory) {
            return new ArrayList<>(children.get(directory.getUri()));
        }
        @Override public InputStream open(GameDocument file) throws FileNotFoundException {
            if (unreadable.contains(file.getUri())) throw new FileNotFoundException(file.getUri());
            return new ByteArrayInputStream(contents.get(file.getUri()));
        }

        private static String uri(String path) { return "memory://root/" + path; }
        private static String uriForParent(String parent) {
            return parent.isEmpty() ? "memory://root" : uri(parent);
        }
        private static String parent(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? "" : path.substring(0, slash);
        }
        private static String name(String path) {
            int slash = path.lastIndexOf('/');
            return slash < 0 ? path : path.substring(slash + 1);
        }
    }
}
