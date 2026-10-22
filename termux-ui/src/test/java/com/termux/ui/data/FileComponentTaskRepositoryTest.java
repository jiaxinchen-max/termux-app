package com.termux.ui.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class FileComponentTaskRepositoryTest {

    private static final String SHA256 =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesAndLoadsCompleteTaskSnapshot() throws Exception {
        File directory = temporaryFolder.newFolder("tasks");
        FileComponentTaskRepository repository = new FileComponentTaskRepository(directory);
        ComponentTask task = ComponentTask.queued("task-1", "wine", 9,
            "https://example.invalid/wine.tar.xz", 100, SHA256)
            .transition(ComponentTaskState.DOWNLOADING, 40, "\"v1\"",
                "Wed, 27 Aug 2026 00:00:00 GMT", "", "");

        repository.save(task);
        ComponentTask restored = repository.find("task-1").get();

        assertEquals(task.getTaskId(), restored.getTaskId());
        assertEquals(task.getDownloadedBytes(), restored.getDownloadedBytes());
        assertEquals(task.getEtag(), restored.getEtag());
        assertEquals(ComponentTaskState.DOWNLOADING, restored.getState());
        assertEquals(1, repository.list().size());
    }

    @Test
    public void savesAndLoadsTaskWithEmptySha256() throws Exception {
        // A package-managed component (termux-glibc) carries no archive hash, so its task is
        // persisted with an empty sha256. Loading it back must not reject the empty value --
        // ComponentTask itself treats "" as "not yet known". Regression for an install that
        // failed with "Missing component task property: sha256".
        File directory = temporaryFolder.newFolder("tasks");
        FileComponentTaskRepository repository = new FileComponentTaskRepository(directory);
        ComponentTask task = ComponentTask.queued("termux-glibc-task", "termux-glibc", 1,
            "https://github.com/termux/termux-packages", 1, "");

        repository.save(task);
        ComponentTask restored = repository.find("termux-glibc-task").get();

        assertEquals("", restored.getSha256());
        assertEquals("termux-glibc", restored.getPackageName());
    }

    @Test
    public void listRecoversInterruptedReplacementBackup() throws Exception {
        File directory = temporaryFolder.newFolder("tasks");
        FileComponentTaskRepository repository = new FileComponentTaskRepository(directory);
        ComponentTask task = ComponentTask.queued("task-1", "wine", 9,
            "https://example.invalid/wine.tar.xz", 100, SHA256);
        repository.save(task);
        File target = new File(directory, "task-1.properties");
        File backup = new File(directory, "task-1.properties.bak");
        assertFalse(backup.exists());
        if (!target.renameTo(backup)) {
            throw new IOException("test setup failed");
        }

        assertEquals(1, repository.list().size());
        assertFalse(backup.exists());
    }

    @Test(expected = IOException.class)
    public void rejectsUnknownSchema() throws Exception {
        File directory = temporaryFolder.newFolder("tasks");
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", "99");
        try (FileOutputStream output = new FileOutputStream(
            new File(directory, "task-1.properties"))) {
            properties.store(output, "invalid");
        }

        new FileComponentTaskRepository(directory).find("task-1");
    }
}
