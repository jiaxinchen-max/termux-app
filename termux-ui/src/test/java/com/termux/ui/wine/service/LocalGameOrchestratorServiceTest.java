package com.termux.ui.wine.service;

import com.termux.ui.wine.runtime.PreflightIssue;
import com.termux.ui.wine.runtime.PreflightIssueCode;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
