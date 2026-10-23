package com.termux.ui.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.ui.domain.RootfsBackupTask;
import com.termux.ui.domain.RootfsBackupTaskState;

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

    @Test
    public void prefixTemplatesAreIncludedByDefault() {
        assertTrue(RootfsBackupTask.queued("t6", RootfsBackupTask.Kind.BACKUP, 1L).includesPrefixTemplates());
        assertTrue(RootfsBackupTask.queued("t7", RootfsBackupTask.Kind.BACKUP, "/a/b.tar.zst", 1L)
            .includesPrefixTemplates());
    }

    @Test
    public void leavingTemplatesOutSurvivesTransitionsAndTheRepository() throws IOException {
        FileRootfsBackupTaskRepository repository = new FileRootfsBackupTaskRepository(temp.newFolder("tasks2"));
        RootfsBackupTask queued = RootfsBackupTask.queued("t8", RootfsBackupTask.Kind.BACKUP,
            "/storage/emulated/0/Backups/a.tar.zst", false, 10L);
        repository.save(queued);
        RootfsBackupTask running = queued.transition(RootfsBackupTaskState.RUNNING, "", 11L);
        repository.save(running);
        RootfsBackupTask loaded = repository.find("t8").get();
        assertFalse(loaded.includesPrefixTemplates());
        assertEquals(RootfsBackupTaskState.RUNNING, loaded.getState());
    }

    @Test
    public void taskRecordWithoutTheFlagCountsAsIncludingTemplates() throws IOException {
        File tasks = temp.newFolder("legacy-tasks");
        FileRootfsBackupTaskRepository repository = new FileRootfsBackupTaskRepository(tasks);
        repository.save(RootfsBackupTask.queued("t9", RootfsBackupTask.Kind.BACKUP, 5L));
        File record = new File(tasks, "t9.properties");
        String text = new String(Files.readAllBytes(record.toPath()), StandardCharsets.UTF_8)
            .replaceAll("(?m)^includePrefixTemplates=.*\\R", "");
        Files.write(record.toPath(), text.getBytes(StandardCharsets.UTF_8));
        assertTrue(repository.find("t9").get().includesPrefixTemplates());
    }

    @Test
    public void specCarriesTheTemplateChoice() throws IOException {
        File archive = new File(temp.getRoot(), "c.tar.zst");
        for (boolean include : new boolean[] {true, false}) {
            File spec = new File(temp.newFolder("specs-" + include), "t.backupspec");
            RootfsBackupTask task = RootfsBackupTask.queued("t10", RootfsBackupTask.Kind.BACKUP, null,
                include, 1L);
            new RootfsBackupSpecCodec().write(spec, task, archive, new File(temp.getRoot(), "e"),
                new File(temp.getRoot(), "l"));
            String text = new String(Files.readAllBytes(spec.toPath()), StandardCharsets.UTF_8);
            assertTrue(text.contains("includePrefixTemplates=" + include + "\n"));
        }
    }
}
