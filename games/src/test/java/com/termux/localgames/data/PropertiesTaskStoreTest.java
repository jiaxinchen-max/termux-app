package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

public class PropertiesTaskStoreTest {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void savesAndFindsTask() throws Exception {
        File directory = temporary.newFolder("tasks");
        PropertiesTaskStore<FakeTask> store = newStore(directory);
        store.save(new FakeTask("task-1", FakeTarget.ALPHA));

        Optional<FakeTask> restored = store.find("task-1");

        assertTrue(restored.isPresent());
        assertEquals(FakeTarget.ALPHA, restored.get().target);
        assertTrue(new File(directory, "task-1.properties").isFile());
    }

    @Test
    public void findReturnsEmptyWhenMissing() throws Exception {
        File directory = temporary.newFolder("tasks");
        PropertiesTaskStore<FakeTask> store = newStore(directory);

        assertFalse(store.find("missing").isPresent());
    }

    @Test
    public void listRecoversInterruptedReplacementBackup() throws Exception {
        File directory = temporary.newFolder("tasks");
        PropertiesTaskStore<FakeTask> store = newStore(directory);
        store.save(new FakeTask("task-1", FakeTarget.ALPHA));
        File target = new File(directory, "task-1.properties");
        File backup = new File(directory, "task-1.properties.bak");
        assertFalse(backup.exists());
        assertTrue(target.renameTo(backup));

        List<FakeTask> tasks = store.list();

        assertEquals(1, tasks.size());
        assertEquals("task-1", tasks.get(0).taskId);
        assertTrue(target.isFile());
        assertFalse(backup.exists());
    }

    @Test
    public void listReturnsEmptyForMissingDirectory() throws Exception {
        File directory = new File(temporary.getRoot(), "does-not-exist");
        PropertiesTaskStore<FakeTask> store = newStore(directory);

        assertTrue(store.list().isEmpty());
    }

    /**
     * Regression test for the reproduced bug: {@code FileResetTaskRepository.list()} scanned a
     * directory containing a legacy/corrupt record (a {@code target=ROOTFS_REBUILD} value that
     * predates the current enum) and aborted the *entire* scan, so every caller -- including the
     * reset-reuse guard and {@code reconcileAll()} -- believed nothing was active. A single
     * unparseable file must be skipped, not allowed to poison the whole listing.
     */
    @Test
    public void listSkipsUnparseableFileInsteadOfFailingWholeScan() throws Exception {
        File directory = temporary.newFolder("tasks");
        PropertiesTaskStore<FakeTask> store = newStore(directory);
        store.save(new FakeTask("good-task", FakeTarget.ALPHA));

        Properties corrupt = new Properties();
        corrupt.setProperty("taskId", "bad-task");
        corrupt.setProperty("target", "ROOTFS_REBUILD");
        try (FileOutputStream output = new FileOutputStream(
            new File(directory, "bad-task.properties"))) {
            corrupt.store(output, "corrupt fixture");
        }

        List<FakeTask> tasks = store.list();

        assertEquals(1, tasks.size());
        assertEquals("good-task", tasks.get(0).taskId);
    }

    private static PropertiesTaskStore<FakeTask> newStore(File directory) {
        return new PropertiesTaskStore<>(directory, new FakeTaskCodec(), ID_PATTERN, "Fake task");
    }

    private enum FakeTarget { ALPHA, BETA }

    private static final class FakeTask {
        final String taskId;
        final FakeTarget target;

        FakeTask(String taskId, FakeTarget target) {
            this.taskId = taskId;
            this.target = target;
        }
    }

    private static final class FakeTaskCodec implements PropertiesTaskStore.Codec<FakeTask> {
        @Override
        public String taskId(FakeTask task) {
            return task.taskId;
        }

        @Override
        public Properties toProperties(FakeTask task) {
            Properties value = new Properties();
            value.setProperty("taskId", task.taskId);
            value.setProperty("target", task.target.name());
            return value;
        }

        @Override
        public FakeTask fromProperties(Properties value) throws IOException {
            String taskId = value.getProperty("taskId");
            String target = value.getProperty("target");
            if (taskId == null || target == null) throw new IOException("fake_task_field_missing");
            try {
                return new FakeTask(taskId, FakeTarget.valueOf(target));
            } catch (IllegalArgumentException error) {
                throw new IOException("fake_task_invalid", error);
            }
        }
    }
}
