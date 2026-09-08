package com.termux.localgames.service;

import static org.junit.Assert.assertEquals;

import com.termux.localgames.runtime.PreflightIssue;
import com.termux.localgames.runtime.PreflightIssueCode;

import org.junit.Test;

public class LocalGameOrchestratorServiceTest {

    @Test
    public void preflightErrorIncludesSafeSubject() {
        assertEquals("preflight_component_missing:scripts",
            LocalGameOrchestratorService.preflightError(new PreflightIssue(
                PreflightIssueCode.COMPONENT_MISSING, "scripts")));
    }

    @Test
    public void preflightErrorDropsUnsafeSubject() {
        assertEquals("preflight_component_missing",
            LocalGameOrchestratorService.preflightError(new PreflightIssue(
                PreflightIssueCode.COMPONENT_MISSING, "../scripts")));
    }
}
