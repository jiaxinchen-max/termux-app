package com.termux.ui.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionOverridesTest {

    @Test
    public void desktopWithoutOverrideFollowsGlobalTouchMode() {
        assertEquals("1", DesktopSessionOverrides.effectiveTouchMode(null, "1"));
        assertEquals("3", DesktopSessionOverrides.effectiveTouchMode(null, "3"));
    }

    @Test
    public void desktopOverrideWinsOverGlobalTouchMode() {
        assertEquals("2", DesktopSessionOverrides.effectiveTouchMode("2", "1"));
        assertEquals("1", DesktopSessionOverrides.effectiveTouchMode("1", "3"));
    }

    @Test
    public void unknownOverrideIsIgnored() {
        assertEquals("1", DesktopSessionOverrides.effectiveTouchMode("9", "1"));
        assertFalse(DesktopSessionOverrides.isTouchMode(""));
        assertFalse(DesktopSessionOverrides.isTouchMode(null));
        assertTrue(DesktopSessionOverrides.isTouchMode("2"));
    }
}
