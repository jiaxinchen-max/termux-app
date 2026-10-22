package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.x11.X11SessionView.PointerMode;

import org.junit.Test;

public class GamePointerOptionsTest {

    @Test
    public void lockingTheCursorWinsOverTheTouchScreenSwitch() {
        assertEquals(PointerMode.LOCKED_CURSOR, GamePointerOptions.modeFor(true, false));
        assertEquals(PointerMode.LOCKED_CURSOR, GamePointerOptions.modeFor(true, true));
    }

    @Test
    public void touchScreenAloneIsTouchScreenMode() {
        assertEquals(PointerMode.TOUCH_SCREEN, GamePointerOptions.modeFor(false, true));
    }

    @Test
    public void neitherSwitchOnIsTouchpadMode() {
        assertEquals(PointerMode.TOUCHPAD, GamePointerOptions.modeFor(false, false));
    }

    @Test
    public void everyModeRoundTripsThroughItsSwitches() {
        for (PointerMode mode : PointerMode.values()) {
            assertEquals(mode, GamePointerOptions.modeFor(
                GamePointerOptions.isLockCursor(mode), GamePointerOptions.isTouchScreen(mode)));
        }
    }

    @Test
    public void onlyOneSwitchReadsOnForAnyMode() {
        for (PointerMode mode : PointerMode.values()) {
            assertFalse(GamePointerOptions.isLockCursor(mode) && GamePointerOptions.isTouchScreen(mode));
        }
        assertTrue(GamePointerOptions.isLockCursor(PointerMode.LOCKED_CURSOR));
        assertTrue(GamePointerOptions.isTouchScreen(PointerMode.TOUCH_SCREEN));
        assertFalse(GamePointerOptions.isLockCursor(PointerMode.TOUCHPAD));
        assertFalse(GamePointerOptions.isTouchScreen(PointerMode.TOUCHPAD));
    }

    @Test
    public void touchScreenSwitchIsDisabledOnlyWhileTheCursorIsLocked() {
        assertFalse(GamePointerOptions.isTouchScreenSwitchEnabled(PointerMode.LOCKED_CURSOR));
        assertTrue(GamePointerOptions.isTouchScreenSwitchEnabled(PointerMode.TOUCH_SCREEN));
        assertTrue(GamePointerOptions.isTouchScreenSwitchEnabled(PointerMode.TOUCHPAD));
    }

    @Test
    public void turningLockOffFromLockedLeavesTouchpadMode() {
        // The old Input dialog left the touch screen box unchecked after unlocking the cursor.
        assertEquals(PointerMode.TOUCHPAD, GamePointerOptions.modeFor(false, false));
    }
}
