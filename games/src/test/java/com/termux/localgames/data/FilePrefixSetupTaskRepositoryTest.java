package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.PrefixSetupTask;
import com.termux.localgames.domain.RuntimeSetupTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FilePrefixSetupTaskRepositoryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void persistsBuildStateAcrossRepositoryInstances() throws Exception {
        java.io.File directory = temporary.newFolder("prefix-setup-tasks");
        FilePrefixSetupTaskRepository first =
            new FilePrefixSetupTaskRepository(directory);
        PrefixSetupTask queued = PrefixSetupTask.queued("prefix-task-1", "game-1",
            "wine-9.3-vanilla-wow64", 10);
        first.save(queued.transition(RuntimeSetupTaskState.BUILDING, "", 20));

        PrefixSetupTask restored = new FilePrefixSetupTaskRepository(directory)
            .find("prefix-task-1").get();

        assertEquals(RuntimeSetupTaskState.BUILDING, restored.getState());
        assertEquals("game-1", restored.getGameId());
        assertEquals("wine-9.3-vanilla-wow64", restored.getWinePackage());
        assertTrue(new java.io.File(directory, "prefix-task-1.properties").isFile());
    }
}
