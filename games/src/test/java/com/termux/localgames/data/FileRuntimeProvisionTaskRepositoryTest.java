package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.RuntimeProvisionTask;
import com.termux.localgames.domain.RuntimeProvisionTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileRuntimeProvisionTaskRepositoryTest {
    private static final String SHA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void persistsBuildStateAcrossRepositoryInstances() throws Exception {
        java.io.File directory = temporary.newFolder("provision-tasks");
        FileRuntimeProvisionTaskRepository first =
            new FileRuntimeProvisionTaskRepository(directory);
        RuntimeProvisionTask queued = RuntimeProvisionTask.queued("task-1",
            "debian-13-games-rootfs", 1, SHA, "hangover-11.9-debian13-source",
            "games-debian13-v1-aaaaaaaaaaaa", 10);
        first.save(queued.transition(RuntimeProvisionTaskState.BUILDING, "", 20));

        RuntimeProvisionTask restored = new FileRuntimeProvisionTaskRepository(directory)
            .find("task-1").get();

        assertEquals(RuntimeProvisionTaskState.BUILDING, restored.getState());
        assertEquals(SHA, restored.getRecipeSha256());
        assertEquals("games-debian13-v1-aaaaaaaaaaaa", restored.getContainerName());
        assertTrue(new java.io.File(directory, "task-1.properties").isFile());
    }
}
