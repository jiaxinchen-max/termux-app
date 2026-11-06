package com.termux.ui.wine.runtime;

import com.termux.ui.domain.XServerBackendType;
import com.termux.ui.wine.domain.LaunchStage;
import com.termux.ui.wine.domain.LaunchTaskState;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SessionOpenPolicyTest {

    private static final LaunchTaskState LIVE = LaunchTaskState.RUNNING;

    @Test
    public void legacyOpensOnlyWhenWaitingForFirstFrame() {
        XServerBackendType b = XServerBackendType.LEGACY_IN_PROCESS;
        assertFalse(SessionOpenPolicy.shouldOpen(b, LaunchStage.STARTING_DISPLAY, LIVE));
        assertTrue(SessionOpenPolicy.shouldOpen(b, LaunchStage.WAITING_FIRST_FRAME, LIVE));
        assertTrue(SessionOpenPolicy.shouldOpen(b, LaunchStage.RUNNING, LIVE));
    }

    @Test
    public void xlorieOpensBeforeTheServerStarts() {
        XServerBackendType b = XServerBackendType.XLORIE_EXTERNAL;
        assertFalse(SessionOpenPolicy.shouldOpen(b, LaunchStage.PRECHECK, LIVE));
        assertTrue(SessionOpenPolicy.shouldOpen(b, LaunchStage.PREPARING_PREFIX, LIVE));
        assertTrue(SessionOpenPolicy.shouldOpen(b, LaunchStage.STARTING_DISPLAY, LIVE));
        assertTrue(SessionOpenPolicy.shouldOpen(b, LaunchStage.RUNNING, LIVE));
        assertFalse(SessionOpenPolicy.shouldOpen(b, LaunchStage.STOPPING, LIVE));
    }

    @Test
    public void terminalTasksNeverOpen() {
        assertFalse(SessionOpenPolicy.shouldOpen(XServerBackendType.XLORIE_EXTERNAL,
            LaunchStage.STARTING_DISPLAY, LaunchTaskState.FAILED));
    }
}
