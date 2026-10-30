package com.termux.ui.runtime;

import static com.termux.ui.domain.DesktopSessionState.FAILED;
import static com.termux.ui.domain.DesktopSessionState.INSTALLED;
import static com.termux.ui.domain.DesktopSessionState.NOT_INSTALLED;
import static com.termux.ui.domain.DesktopSessionState.RUNNING;
import static com.termux.ui.domain.DesktopSessionState.STARTING;
import static com.termux.ui.domain.DesktopSessionState.STOPPING;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DesktopSessionStateResolverTest {

    @Test
    public void notInstalledWinsOverEverything() {
        assertEquals(NOT_INSTALLED, DesktopSessionStateResolver.resolve(false, true, true, true, "starting"));
    }

    @Test
    public void aliveProcessIsStartingUntilSocketAndMarkerAppear() {
        assertEquals(STARTING, DesktopSessionStateResolver.resolve(true, true, false, false, "starting"));
        assertEquals(STARTING, DesktopSessionStateResolver.resolve(true, true, true, false, "starting"));
        assertEquals(STARTING, DesktopSessionStateResolver.resolve(true, true, false, true, "starting"));
        assertEquals(RUNNING, DesktopSessionStateResolver.resolve(true, true, true, true, "starting"));
    }

    @Test
    public void stoppedOrNeverStartedIsInstalled() {
        assertEquals(INSTALLED, DesktopSessionStateResolver.resolve(true, false, false, false, null));
        assertEquals(INSTALLED, DesktopSessionStateResolver.resolve(true, false, false, false, ""));
        assertEquals(INSTALLED, DesktopSessionStateResolver.resolve(true, false, false, false, "stopped"));
        assertEquals(INSTALLED, DesktopSessionStateResolver.resolve(true, false, false, false, "exit:0"));
    }

    @Test
    public void deadProcessWithoutCleanStopIsFailed() {
        assertEquals(FAILED, DesktopSessionStateResolver.resolve(true, false, false, false, "exit:66"));
        assertEquals(FAILED, DesktopSessionStateResolver.resolve(true, false, false, false, "exit:143"));
        assertEquals(FAILED, DesktopSessionStateResolver.resolve(true, false, false, false, "starting"));
    }

    @Test public void stoppingWinsOverPidSocketAndReadyMarkerDuringCleanup() {
        for (boolean alive : new boolean[]{false, true})
            for (boolean socket : new boolean[]{false, true})
                for (boolean ready : new boolean[]{false, true})
                    assertEquals(STOPPING, DesktopSessionStateResolver.resolve(true, alive, socket, ready, "stopping"));
    }

    @Test public void requestedStopDoesNotFlashStartingOrFailedBeforeSupervisorPublishesStopped() {
        assertEquals(RUNNING, DesktopSessionStateResolver.resolve(true, true, true, true, "starting"));
        assertEquals(STOPPING, DesktopSessionStateResolver.resolve(true, true, true, true, "stopping"));
        assertEquals(STOPPING, DesktopSessionStateResolver.resolve(true, true, true, false, "stopping"));
        assertEquals(STOPPING, DesktopSessionStateResolver.resolve(true, false, true, false, "stopping"));
        assertEquals(INSTALLED, DesktopSessionStateResolver.resolve(true, false, false, false, "stopped"));
        // The PID snapshot can still predate the final status read in this refresh.
        assertEquals(INSTALLED, DesktopSessionStateResolver.resolve(true, true, true, false, "stopped"));
        assertEquals(INSTALLED, DesktopSessionStateResolver.resolve(true, true, true, true, "exit:0"));
    }

    @Test public void cleanupFailuresAreNotHiddenByAnOldAlivePidOrReadyMarker() {
        for (String status : new String[]{"exit:1", "exit:74", "exit:124", "exit:130", "exit:143", "exit:01", "exit:0invalid"}) {
            assertTrue(DesktopSessionStateResolver.isFailureStatus(status));
            assertEquals(FAILED, DesktopSessionStateResolver.resolve(true, true, true, true, status));
            assertEquals(FAILED, DesktopSessionStateResolver.resolve(true, false, false, false, status));
        }
        assertFalse(DesktopSessionStateResolver.isFailureStatus("stopping"));
        assertFalse(DesktopSessionStateResolver.isFailureStatus("exit:0"));
        assertFalse(DesktopSessionStateResolver.isFailureStatus(null));
    }

    @Test public void stoppingStillOwnsRuntimeAndBlocksActiveSessionMutations() {
        assertTrue(STOPPING.isActive());
        assertTrue(STARTING.isActive());
        assertTrue(RUNNING.isActive());
        assertFalse(INSTALLED.isActive());
        assertFalse(FAILED.isActive());
        assertFalse(NOT_INSTALLED.isActive());
    }
}
