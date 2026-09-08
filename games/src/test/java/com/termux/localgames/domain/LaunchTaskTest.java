package com.termux.localgames.domain;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LaunchTaskTest {

    @Test
    public void transitionReturnsNewSnapshotAndPreservesIdentity() {
        LaunchTask queued = LaunchTask.queued("task-1", "game-1", 100);

        LaunchTask running = queued.transition(LaunchTaskState.RUNNING,
            LaunchStage.PRECHECK, 10, -1, null, "", false, "log/task-1");
        LaunchTask succeeded = running.transition(LaunchTaskState.SUCCEEDED,
            LaunchStage.COMPLETE, 100, 123, 0, "", false, "log/task-1");

        assertEquals(LaunchTaskState.QUEUED, queued.getState());
        assertEquals("task-1", running.getTaskId());
        assertEquals(LaunchTaskState.SUCCEEDED, succeeded.getState());
        assertEquals(Integer.valueOf(0), succeeded.getExitCode());
    }

    @Test(expected = IllegalStateException.class)
    public void terminalTaskCannotTransition() {
        LaunchTask task = LaunchTask.queued("task-1", "game-1", 100)
            .transition(LaunchTaskState.CANCELLED, LaunchStage.CLEANING,
                20, -1, null, "", false, "");

        task.transition(LaunchTaskState.RUNNING, LaunchStage.PRECHECK,
            30, -1, null, "", false, "");
    }

    @Test(expected = IllegalArgumentException.class)
    public void progressCannotDecrease() {
        LaunchTask task = LaunchTask.queued("task-1", "game-1", 100)
            .transition(LaunchTaskState.RUNNING, LaunchStage.PRECHECK,
                20, -1, null, "", false, "");

        task.transition(LaunchTaskState.RUNNING, LaunchStage.PREPARING_COMPONENTS,
            10, -1, null, "", false, "");
    }

    @Test
    public void fingerprintAndEventCursorSurviveTransition() {
        LaunchTask task = LaunchTask.queued("task-1", "game-1", 100)
            .withEnvironmentFingerprint("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
            .applyEvent(LaunchTaskState.RUNNING, LaunchStage.PRECHECK, 5,
                123, null, "", false, "log", 1, 110);

        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            task.getEnvironmentFingerprint());
        assertEquals(1, task.getLastEventSequence());
        assertEquals(110, task.getUpdatedAt());
    }
}
