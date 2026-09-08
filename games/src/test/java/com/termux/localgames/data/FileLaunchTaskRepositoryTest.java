package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Optional;
import java.util.Properties;

public class FileLaunchTaskRepositoryTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void roundTripPersistsRecoveryFieldsAndFindsActiveGame() throws Exception {
        FileLaunchTaskRepository repository = new FileLaunchTaskRepository(temporary.newFolder());
        LaunchTask task = LaunchTask.queued("task-1", "game-1", 100)
            .withEnvironmentFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .applyEvent(LaunchTaskState.RUNNING, LaunchStage.PRECHECK, 5, 321,
                null, "", false, "/logs/task-1", 1, 110);

        repository.save(task);

        LaunchTask restored = repository.find("task-1").get();
        assertEquals(1, restored.getLastEventSequence());
        assertEquals(110, restored.getUpdatedAt());
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            restored.getEnvironmentFingerprint());
        assertEquals("task-1", repository.findActiveForGame("game-1").get().getTaskId());
        assertFalse(restored.isDisplayConnected());
    }

    @Test
    public void persistsDisplayConnectionAndFirstFrame() throws Exception {
        FileLaunchTaskRepository repository = new FileLaunchTaskRepository(temporary.newFolder());
        LaunchTask waiting = LaunchTask.queued("task-1", "game-1", 100)
            .transition(LaunchTaskState.RUNNING, LaunchStage.WAITING_FIRST_FRAME, 90,
                321, null, "", false, "log")
            .withDisplayConnection(true, 110)
            .withFirstFrame(120);
        repository.save(waiting);

        LaunchTask restored = repository.find("task-1").get();
        assertTrue(restored.isDisplayConnected());
        assertEquals(120, restored.getDisplayUpdatedAt());
        assertEquals(120, restored.getFirstFrameAt());
        assertEquals(LaunchStage.RUNNING, restored.getStage());
    }

    @Test
    public void readsSchemaOneWithSafeMigrationDefaults() throws Exception {
        File directory = temporary.newFolder();
        Properties properties = baseV1();
        try (FileOutputStream output = new FileOutputStream(new File(directory, "task-1.properties"))) {
            properties.store(output, null);
        }

        LaunchTask task = new FileLaunchTaskRepository(directory).find("task-1").get();

        assertEquals(0, task.getLastEventSequence());
        assertEquals(task.getStartedAt(), task.getUpdatedAt());
        assertTrue(task.getEnvironmentFingerprint().isEmpty());
    }

    @Test
    public void readsSchemaTwoWithDisplayEvidenceDefaults() throws Exception {
        File directory = temporary.newFolder();
        Properties properties = baseV1();
        properties.setProperty("schemaVersion", "2");
        properties.setProperty("lastEventSequence", "0");
        properties.setProperty("updatedAt", "100");
        properties.setProperty("environmentFingerprint", "");
        try (FileOutputStream output = new FileOutputStream(new File(directory, "task-1.properties"))) {
            properties.store(output, null);
        }

        LaunchTask task = new FileLaunchTaskRepository(directory).find("task-1").get();
        assertFalse(task.isDisplayConnected());
        assertEquals(0, task.getDisplayUpdatedAt());
        assertEquals(0, task.getFirstFrameAt());
    }

    @Test(expected = IOException.class)
    public void rejectsUnknownPersistedField() throws Exception {
        File directory = temporary.newFolder();
        Properties properties = baseV1();
        properties.setProperty("unexpected", "value");
        try (FileOutputStream output = new FileOutputStream(new File(directory, "task-1.properties"))) {
            properties.store(output, null);
        }
        new FileLaunchTaskRepository(directory).find("task-1");
    }

    @Test
    public void terminalTaskIsNotReturnedAsActive() throws Exception {
        FileLaunchTaskRepository repository = new FileLaunchTaskRepository(temporary.newFolder());
        repository.save(LaunchTask.queued("task-1", "game-1", 100)
            .transition(LaunchTaskState.CANCELLED, LaunchStage.COMPLETE, 100,
                -1, null, "", false, ""));
        Optional<LaunchTask> active = repository.findActiveForGame("game-1");
        assertFalse(active.isPresent());
    }

    private static Properties baseV1() {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", "1");
        properties.setProperty("taskId", "task-1");
        properties.setProperty("gameId", "game-1");
        properties.setProperty("state", "QUEUED");
        properties.setProperty("stage", "QUEUED");
        properties.setProperty("progress", "0");
        properties.setProperty("startedAt", "100");
        properties.setProperty("pid", "-1");
        properties.setProperty("exitCode", "");
        properties.setProperty("errorCode", "");
        properties.setProperty("recoverable", "false");
        properties.setProperty("logRef", "");
        return properties;
    }
}
