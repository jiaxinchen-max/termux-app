package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.localgames.domain.ResetTask;
import com.termux.localgames.domain.ResetTarget;
import com.termux.localgames.domain.ResetTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class FileResetTaskRepositoryTest {
    private static final String RECIPE_SHA256 =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void persistsResetStateAcrossRepositoryInstances() throws Exception {
        java.io.File directory = temporary.newFolder("reset-tasks");
        FileResetTaskRepository first = new FileResetTaskRepository(directory);
        ResetTask queued = ResetTask.queued("reset-1", ResetTarget.GLIBC,
            RECIPE_SHA256, 10);
        first.save(queued.transition(ResetTaskState.RUNNING, "", 20));

        ResetTask restored = new FileResetTaskRepository(directory).find("reset-1").get();

        assertEquals(ResetTaskState.RUNNING, restored.getState());
        assertEquals(ResetTarget.GLIBC, restored.getTarget());
        assertEquals(RECIPE_SHA256, restored.getResetKey());
        assertTrue(new java.io.File(directory, "reset-1.properties").isFile());
    }

    @Test
    public void glibcResetHasNoResetKey() {
        ResetTask queued = ResetTask.queued("reset-2", ResetTarget.GLIBC, null, 5);
        assertEquals("", queued.getResetKey());
    }

    @Test
    public void terminalStatesCannotTransitionAgain() {
        ResetTask succeeded = ResetTask.queued("reset-4", ResetTarget.GLIBC, null, 1)
            .transition(ResetTaskState.RUNNING, "", 2)
            .transition(ResetTaskState.SUCCEEDED, "", 3);
        assertTrue(succeeded.getState().isTerminal());
        try {
            succeeded.transition(ResetTaskState.FAILED, "boom", 4);
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            // terminal tasks are immutable from here on
        }
    }

    @Test
    public void nonTerminalStatesAreNotTerminal() {
        assertFalse(ResetTaskState.QUEUED.isTerminal());
        assertFalse(ResetTaskState.RUNNING.isTerminal());
    }
}
