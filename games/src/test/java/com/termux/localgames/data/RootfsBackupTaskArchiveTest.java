package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.RootfsBackupTask;
import com.termux.localgames.domain.RootfsBackupTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

public final class RootfsBackupTaskArchiveTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void repositoryKeepsChosenArchivePathAcrossTransitions() throws IOException {
        FileRootfsBackupTaskRepository repository = new FileRootfsBackupTaskRepository(temp.newFolder("tasks"));
        RootfsBackupTask queued = RootfsBackupTask.queued("t1", RootfsBackupTask.Kind.BACKUP,
            "/storage/emulated/0/Backups/a (1)=b.tar.zst", 10L);
        repository.save(queued);
        RootfsBackupTask running = queued.transition(RootfsBackupTaskState.RUNNING, "", 11L);
        repository.save(running);
        RootfsBackupTask loaded = repository.find("t1").get();
        assertEquals("/storage/emulated/0/Backups/a (1)=b.tar.zst", loaded.getArchivePath());
        assertEquals(RootfsBackupTaskState.RUNNING, loaded.getState());
    }

    @Test
    public void taskWithoutArchiveUsesLegacyDefault() {
        assertEquals("", RootfsBackupTask.queued("t2", RootfsBackupTask.Kind.RESTORE, 1L).getArchivePath());
    }

    @Test(expected = IllegalArgumentException.class)
    public void relativeArchivePathIsRejected() {
        RootfsBackupTask.queued("t3", RootfsBackupTask.Kind.BACKUP, "relative.tar.zst", 1L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void newlineInArchivePathIsRejected() {
        RootfsBackupTask.queued("t4", RootfsBackupTask.Kind.BACKUP, "/a\nb", 1L);
    }

    @Test
    public void specAllowsEqualsAndWritesUtf8() throws IOException {
        File spec = new File(temp.newFolder("specs"), "t.backupspec");
        RootfsBackupTask task = RootfsBackupTask.queued("t5", RootfsBackupTask.Kind.BACKUP, 1L);
        File archive = new File(temp.getRoot(), "a=b.tar.zst");
        new RootfsBackupSpecCodec().write(spec, task, archive, new File(temp.getRoot(), "e"),
            new File(temp.getRoot(), "l"));
        String text = new String(Files.readAllBytes(spec.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("backupArchivePath=" + archive.getCanonicalPath() + "\n"));
        assertTrue(text.contains("mode=backup\n"));
    }
}
