package com.termux.ui.runtime;

import static com.termux.ui.domain.DesktopSessionState.FAILED;
import static com.termux.ui.domain.DesktopSessionState.INSTALLED;
import static com.termux.ui.domain.DesktopSessionState.NOT_INSTALLED;
import static com.termux.ui.domain.DesktopSessionState.RUNNING;
import static com.termux.ui.domain.DesktopSessionState.STARTING;
import static org.junit.Assert.assertEquals;

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
        assertEquals(FAILED, DesktopSessionStateResolver.resolve(true, false, false, false, "stopping"));
    }
}
