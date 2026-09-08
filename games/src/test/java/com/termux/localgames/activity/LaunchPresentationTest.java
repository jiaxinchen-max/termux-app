package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;

import com.termux.localgames.domain.LaunchStage;

import org.junit.Test;

public class LaunchPresentationTest {

    @Test
    public void internalStagesCollapseIntoThreeUserSteps() {
        assertEquals(1, LaunchPresentation.phaseFor(LaunchStage.PRECHECK).getStep());
        assertEquals(2,
            LaunchPresentation.phaseFor(LaunchStage.PREPARING_COMPONENTS).getStep());
        assertEquals(2,
            LaunchPresentation.phaseFor(LaunchStage.PREPARING_PREFIX).getStep());
        assertEquals(3,
            LaunchPresentation.phaseFor(LaunchStage.WAITING_FIRST_FRAME).getStep());
        assertEquals(LaunchPresentation.Phase.RUNNING,
            LaunchPresentation.phaseFor(LaunchStage.RUNNING));
    }

    @Test
    public void preflightErrorsMapToRepairActionsWithoutLeakingCode() {
        assertEquals(LaunchPresentation.RecoveryAction.REAUTHORIZE,
            LaunchPresentation.recoveryFor("preflight_permission_lost", true));
        assertEquals(LaunchPresentation.RecoveryAction.COMPONENTS,
            LaunchPresentation.recoveryFor("preflight_component_missing:dxvk", true));
        assertEquals("dxvk",
            LaunchPresentation.subject("preflight_component_missing:dxvk"));
        assertEquals("preflight_component_missing",
            LaunchPresentation.code("preflight_component_missing:dxvk"));
    }

    @Test
    public void unknownRecoverableFailureOffersRetry() {
        assertEquals(LaunchPresentation.RecoveryAction.RETRY,
            LaunchPresentation.recoveryFor("runner_failed", true));
        assertEquals(LaunchPresentation.RecoveryAction.NONE,
            LaunchPresentation.recoveryFor("runner_failed", false));
    }
}
