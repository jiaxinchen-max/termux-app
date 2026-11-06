package com.termux.ui.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class X11SessionOverridesTest {

    @Test
    public void desktopWithoutOverrideFollowsGlobalTouchMode() {
        assertEquals("1", X11SessionOverrides.effectiveTouchMode(null, "1"));
        assertEquals("3", X11SessionOverrides.effectiveTouchMode(null, "3"));
    }

    @Test
    public void desktopOverrideWinsOverGlobalTouchMode() {
        assertEquals("2", X11SessionOverrides.effectiveTouchMode("2", "1"));
        assertEquals("1", X11SessionOverrides.effectiveTouchMode("1", "3"));
    }

    @Test
    public void unknownOverrideIsIgnored() {
        assertEquals("1", X11SessionOverrides.effectiveTouchMode("9", "1"));
        assertFalse(X11SessionOverrides.isTouchMode(""));
        assertFalse(X11SessionOverrides.isTouchMode(null));
        assertTrue(X11SessionOverrides.isTouchMode("2"));
    }
}
