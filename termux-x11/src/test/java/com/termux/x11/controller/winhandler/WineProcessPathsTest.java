package com.termux.x11.controller.winhandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

public class WineProcessPathsTest {
    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private static final String WFM_CMDLINE = "wfm.exe\0ta/com.termux/files/usr/glibc/glibc_wine_9.3-vanilla/bin/wine\0G:\\wfm.exe\0\0";
    private static final String EXPLORER_CMDLINE = "explorer.exe\0m.termux/files/glibc_wine/bin/wine\0C:\\windows\\system32\\explorer.exe\0/desktop=shell,1280x720\0G:\\wfm.exe\0";

    @Test public void prefersTheExeWhoseNameMatchesTheProcess() {
        assertEquals(Collections.singletonList("G:\\wfm.exe"),
            WineProcessPaths.candidateWindowsPaths(WFM_CMDLINE, "wfm.exe").subList(0, 1));
        assertEquals("C:\\windows\\system32\\explorer.exe",
            WineProcessPaths.candidateWindowsPaths(EXPLORER_CMDLINE, "explorer.exe").get(0));
    }

    @Test public void matchingIsCaseInsensitive() {
        assertEquals("G:\\Games\\WFM.EXE",
            WineProcessPaths.candidateWindowsPaths("x\0G:\\Games\\WFM.EXE\0", "wfm.exe").get(0));
    }

    @Test public void truncatedLinuxNameMatchesTheLongerFileName() {
        String cmdline = "G:\\Game Folder\\VeryLongGameLauncher.exe\0";
        assertEquals("G:\\Game Folder\\VeryLongGameLauncher.exe",
            WineProcessPaths.candidateWindowsPaths(cmdline, "VeryLongGameLau").get(0));
    }

    @Test public void builtinProgramsFallBackToSystem32AndWindows() {
        assertEquals(Arrays.asList("C:\\windows\\system32\\start.exe", "C:\\windows\\start.exe"),
            WineProcessPaths.candidateWindowsPaths("start.exe\0wine\0explorer\0G:\\wfm.exe\0", "start.exe"));
    }

    @Test public void nonWindowsProcessesHaveNoCandidates() {
        assertTrue(WineProcessPaths.candidateWindowsPaths("com.termux\0", "com.termux").isEmpty());
        assertTrue(WineProcessPaths.candidateWindowsPaths("main\0G:\\wfm.exe\0", "main").isEmpty());
        assertTrue(WineProcessPaths.candidateWindowsPaths(null, null).isEmpty());
        assertTrue(WineProcessPaths.candidateWindowsPaths("", "").isEmpty());
    }

    @Test public void readsAnEnvironmentVariable() {
        String environ = "HOME=/home\0WINEPREFIX=/data/prefix\0PWD=/x\0";
        assertEquals("/data/prefix", WineProcessPaths.environmentValue(environ, "WINEPREFIX"));
        assertNull(WineProcessPaths.environmentValue(environ, "MISSING"));
        assertNull(WineProcessPaths.environmentValue(environ, "WINE"));
        assertNull(WineProcessPaths.environmentValue(null, "WINEPREFIX"));
    }

    // ---- mapping to unix files ----

    private File prefixWithGameDrive(File gameDir) throws IOException {
        File prefix = folder.newFolder("prefix");
        File dosdevices = new File(prefix, "dosdevices");
        assertTrue(dosdevices.mkdirs());
        Files.createSymbolicLink(new File(dosdevices, "g:").toPath(), gameDir.toPath());
        return prefix;
    }

    @Test public void mapsADriveThroughDosdevicesIgnoringCase() throws IOException {
        File game = folder.newFolder("Game");
        File exe = new File(new File(game, "Bin"), "Wfm.EXE");
        assertTrue(exe.getParentFile().mkdirs());
        assertTrue(exe.createNewFile());
        File prefix = prefixWithGameDrive(game);
        assertEquals(exe.getCanonicalFile(), WineProcessPaths.toUnixFile(prefix, "G:\\bin\\wfm.exe").getCanonicalFile());
        assertEquals(exe.getCanonicalFile(), WineProcessPaths.toUnixFile(prefix, "g:\\BIN\\WFM.EXE").getCanonicalFile());
    }

    @Test public void driveCFallsBackToDriveCDirectory() throws IOException {
        File prefix = folder.newFolder("prefix");
        File exe = new File(prefix, "drive_c/windows/system32/services.exe");
        assertTrue(exe.getParentFile().mkdirs());
        assertTrue(exe.createNewFile());
        assertEquals(exe.getCanonicalFile(),
            WineProcessPaths.toUnixFile(prefix, "C:\\windows\\system32\\services.exe").getCanonicalFile());
    }

    @Test public void unresolvablePathsGiveNull() throws IOException {
        File game = folder.newFolder("Game");
        File prefix = prefixWithGameDrive(game);
        assertNull(WineProcessPaths.toUnixFile(prefix, "G:\\missing.exe"));
        assertNull(WineProcessPaths.toUnixFile(prefix, "X:\\wfm.exe"));
        assertNull(WineProcessPaths.toUnixFile(prefix, "not a path"));
        assertNull(WineProcessPaths.toUnixFile(prefix, "G:\\"));
        assertTrue(new File(game, "folder.exe").mkdir());
        assertNull(WineProcessPaths.toUnixFile(prefix, "G:\\folder.exe"));
    }

    @Test public void parentDirectorySegmentsAreRejected() throws IOException {
        File game = folder.newFolder("Game");
        File outside = folder.newFile("secret.exe");
        File prefix = prefixWithGameDrive(game);
        assertTrue(outside.exists());
        assertNull(WineProcessPaths.toUnixFile(prefix, "G:\\..\\secret.exe"));
        assertNull(WineProcessPaths.toUnixFile(prefix, "G:\\.\\secret.exe"));
    }
}
