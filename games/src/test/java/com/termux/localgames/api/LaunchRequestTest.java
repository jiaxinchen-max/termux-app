package com.termux.localgames.api;

import static org.junit.Assert.assertEquals;

import com.termux.localgames.domain.LaunchExecutionMode;

import org.junit.Test;

public class LaunchRequestTest {

    @Test
    public void carriesFrozenExecutionModeAndStableSessionName() {
        LaunchRequest request = new LaunchRequest("task-1", "/private/start.sh",
            "/private/task.launchspec", "/private/runtime",
            LaunchExecutionMode.TERMINAL_SESSION);

        assertEquals(LaunchExecutionMode.TERMINAL_SESSION, request.getExecutionMode());
        assertEquals("local-games-task-1", request.getShellName());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsMissingExecutionMode() {
        new LaunchRequest("task-1", "/private/start.sh", "/private/task.launchspec",
            "/private/runtime", null);
    }
}
