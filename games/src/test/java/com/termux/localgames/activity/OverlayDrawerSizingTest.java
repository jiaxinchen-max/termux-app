package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class OverlayDrawerSizingTest {

    private static final float DENSITY = 2f;

    @Test
    public void wideWindowKeepsTheFullDrawerWidth() {
        assertEquals(920, OverlayDrawerSizing.drawerWidthPx(1400, DENSITY));
    }

    @Test
    public void narrowWindowUsesFullWidthMinusMargin() {
        assertEquals(720 - 32, OverlayDrawerSizing.drawerWidthPx(720, DENSITY));
    }

    @Test
    public void degenerateWindowNeverGoesNegative() {
        assertEquals(0, OverlayDrawerSizing.drawerWidthPx(0, DENSITY));
    }

    @Test
    public void landscapePhoneKeepsLeftNavigation() {
        assertFalse(OverlayDrawerSizing.compactNavigation(640, 360));
    }

    @Test
    public void narrowOrPortraitWindowsUseCompactNavigation() {
        assertTrue(OverlayDrawerSizing.compactNavigation(360, 640));
        assertTrue(OverlayDrawerSizing.compactNavigation(540, 300));
        assertTrue(OverlayDrawerSizing.compactNavigation(700, 900));
        assertFalse(OverlayDrawerSizing.compactNavigation(560, 560));
    }
}
