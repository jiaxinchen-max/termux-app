package com.termux.ui.activity;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackExitConfirmationTest {
    @Test public void firstBackPromptsAndSecondInsideWindowConfirms() {
        BackExitConfirmation guard = new BackExitConfirmation();
        assertFalse(guard.onBack(0));
        assertTrue(guard.onBack(1000));
        assertFalse(guard.onBack(1100));
    }

    @Test public void expiredPressStartsANewWindow() {
        BackExitConfirmation guard = new BackExitConfirmation();
        assertFalse(guard.onBack(100));
        assertFalse(guard.onBack(2100));
        assertTrue(guard.onBack(2200));
    }

    @Test public void navigationOrBackgroundingInvalidatesConfirmation() {
        BackExitConfirmation guard = new BackExitConfirmation();
        assertFalse(guard.onBack(100));
        guard.reset();
        assertFalse(guard.onBack(200));
        assertTrue(guard.onBack(300));
    }

    @Test public void clockResetCannotConfirmAnOldPress() {
        BackExitConfirmation guard = new BackExitConfirmation();
        assertFalse(guard.onBack(1000));
        assertFalse(guard.onBack(10));
        assertTrue(guard.onBack(20));
    }
}
