package com.termux.ui.wine.importer;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class GameWorkingDirectoryTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void displaysRealDirectoryAndPersistsRelativePath() throws Exception {
        File root = temp.newFolder("game");
        File bin = new File(root, "bin"); assertTrue(bin.mkdir());
        assertEquals(root.getCanonicalPath(), GameWorkingDirectory.display(root, "."));
        assertEquals(bin.getCanonicalPath(), GameWorkingDirectory.display(root, "bin"));
        assertEquals(".", GameWorkingDirectory.relative(root, root.getPath()));
        assertEquals("bin", GameWorkingDirectory.relative(root, bin.getPath()));
        assertEquals("bin", GameWorkingDirectory.relative(root, "bin"));
    }

    @Test public void rejectsMissingEmptyTraversalAndOutsideDirectory() throws Exception {
        File root = temp.newFolder("game");
        File outside = temp.newFolder("outside");
        for (String value : new String[]{"", "missing", "../outside", outside.getPath()}) {
            try { GameWorkingDirectory.relative(root, value); fail(value); }
            catch (IOException expected) { }
        }
    }

    @Test public void rejectsSymlinkEscapeInBothDirections() throws Exception {
        File root = temp.newFolder("game");
        File outside = temp.newFolder("outside");
        File link = new File(root, "link");
        Files.createSymbolicLink(link.toPath(), outside.toPath());
        try { GameWorkingDirectory.relative(root, link.getPath()); fail(); }
        catch (IOException expected) { }
        try { GameWorkingDirectory.display(root, "link"); fail(); }
        catch (IOException expected) { }
    }
}
