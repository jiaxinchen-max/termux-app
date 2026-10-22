package com.termux.ui.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.ui.domain.PrefixSetupTask;
import com.termux.ui.domain.RuntimeSetupTaskState;

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
            "glibc_wine_9.3-vanilla", 10);
        first.save(queued.transition(RuntimeSetupTaskState.BUILDING, "", 20));

        PrefixSetupTask restored = new FilePrefixSetupTaskRepository(directory)
            .find("prefix-task-1").get();

        assertEquals(RuntimeSetupTaskState.BUILDING, restored.getState());
        assertEquals("game-1", restored.getGameId());
        assertEquals("glibc_wine_9.3-vanilla", restored.getWinePackage());
        assertTrue(new java.io.File(directory, "prefix-task-1.properties").isFile());
    }
}
