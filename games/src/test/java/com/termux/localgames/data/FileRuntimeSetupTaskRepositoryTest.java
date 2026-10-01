package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.RuntimeSetupTask;
import com.termux.localgames.domain.RuntimeSetupTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileRuntimeSetupTaskRepositoryTest {
    private static final String SHA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void persistsBuildStateAcrossRepositoryInstances() throws Exception {
        java.io.File directory = temporary.newFolder("setup-tasks");
        FileRuntimeSetupTaskRepository first =
            new FileRuntimeSetupTaskRepository(directory);
        RuntimeSetupTask queued = RuntimeSetupTask.queued("task-1",
            "debian-13-games-rootfs", 1, SHA, "hangover-11.9-debian13-source",
            "container-game-a", "container-game-a", 10);
        first.save(queued.transition(RuntimeSetupTaskState.BUILDING, "", 20));

        RuntimeSetupTask restored = new FileRuntimeSetupTaskRepository(directory)
            .find("task-1").get();

        assertEquals(RuntimeSetupTaskState.BUILDING, restored.getState());
        assertEquals(SHA, restored.getRecipeSha256());
        assertEquals("container-game-a", restored.getContainerId());
        assertEquals("container-game-a", restored.getContainerName());
        assertTrue(new java.io.File(directory, "task-1.properties").isFile());
    }
}
