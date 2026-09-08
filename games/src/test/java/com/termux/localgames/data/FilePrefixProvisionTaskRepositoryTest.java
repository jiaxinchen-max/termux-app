package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.PrefixProvisionTask;
import com.termux.localgames.domain.RuntimeProvisionTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FilePrefixProvisionTaskRepositoryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void persistsBuildStateAcrossRepositoryInstances() throws Exception {
        java.io.File directory = temporary.newFolder("prefix-provision-tasks");
        FilePrefixProvisionTaskRepository first =
            new FilePrefixProvisionTaskRepository(directory);
        PrefixProvisionTask queued = PrefixProvisionTask.queued("prefix-task-1", "game-1",
            "wine-9.3-vanilla-wow64", 10);
        first.save(queued.transition(RuntimeProvisionTaskState.BUILDING, "", 20));

        PrefixProvisionTask restored = new FilePrefixProvisionTaskRepository(directory)
            .find("prefix-task-1").get();

        assertEquals(RuntimeProvisionTaskState.BUILDING, restored.getState());
        assertEquals("game-1", restored.getGameId());
        assertEquals("wine-9.3-vanilla-wow64", restored.getWinePackage());
        assertTrue(new java.io.File(directory, "prefix-task-1.properties").isFile());
    }
}
