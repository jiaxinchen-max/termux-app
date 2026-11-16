package com.termux.ui.desktop.data;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import static org.junit.Assert.*;

public class PreparedDesktopBackupTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void failedPreparationRestoresPriorBytesAndRemovesNewFiles() throws Exception {
        File previous = temp.newFile("prepared-session.json"), added = new File(temp.getRoot(), "new-session.sh");
        Files.write(previous.toPath(), "usable off session".getBytes("UTF-8"));
        PreparedDesktopBackup backup = new PreparedDesktopBackup(new File(temp.getRoot(), "backup"), temp.getRoot());
        backup.capture(Arrays.asList(previous, added));
        Files.write(previous.toPath(), "incomplete USB candidate".getBytes("UTF-8"));
        Files.write(added.toPath(), "candidate only".getBytes("UTF-8"));
        backup.restore(); backup.discard();
        assertEquals("usable off session", new String(Files.readAllBytes(previous.toPath()), "UTF-8"));
        assertFalse(added.exists());
    }
    @Test public void committedPreparationKeepsCandidateFiles() throws Exception {
        File previous = temp.newFile("spec"); Files.write(previous.toPath(), new byte[] {1});
        PreparedDesktopBackup backup = new PreparedDesktopBackup(new File(temp.getRoot(), "backup"), temp.getRoot());
        backup.capture(Arrays.asList(previous));
        Files.write(previous.toPath(), new byte[] {2});
        backup.discard(); backup.restore();
        assertArrayEquals(new byte[] {2}, Files.readAllBytes(previous.toPath()));
    }
    @Test public void symlinkedGuestDirectoryCannotRedirectRestore() throws Exception {
        File managed = temp.newFolder("guest"), original = new File(managed, "session");
        Files.write(original.toPath(), new byte[] {1});
        PreparedDesktopBackup backup = new PreparedDesktopBackup(new File(temp.getRoot(), "backup"), temp.getRoot());
        backup.capture(Arrays.asList(original));
        Files.delete(original.toPath()); Files.delete(managed.toPath());
        File unrelated = temp.newFolder("unrelated");
        Files.createSymbolicLink(managed.toPath(), unrelated.toPath());
        assertThrows(IOException.class, backup::restore);
        assertFalse(new File(unrelated, "session").exists());
    }
}
