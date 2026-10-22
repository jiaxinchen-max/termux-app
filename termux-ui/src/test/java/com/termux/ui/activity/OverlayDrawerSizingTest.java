package com.termux.ui.activity;

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

    @Test
    public void compactWindowsGetABottomSheetAndWideOnesTheSideDrawer() {
        assertEquals(OverlayDrawerSizing.Placement.BOTTOM_SHEET, OverlayDrawerSizing.placementFor(360, 640));
        assertEquals(OverlayDrawerSizing.Placement.BOTTOM_SHEET, OverlayDrawerSizing.placementFor(700, 900));
        assertEquals(OverlayDrawerSizing.Placement.SIDE, OverlayDrawerSizing.placementFor(640, 360));
        assertEquals(OverlayDrawerSizing.Placement.SIDE, OverlayDrawerSizing.placementFor(560, 560));
    }

    @Test
    public void placementFollowsCompactNavigationEverywhere() {
        for (int width = 200; width <= 1000; width += 40) {
            for (int height = 200; height <= 1000; height += 40) {
                boolean sheet = OverlayDrawerSizing.placementFor(width, height)
                    == OverlayDrawerSizing.Placement.BOTTOM_SHEET;
                assertEquals(OverlayDrawerSizing.compactNavigation(width, height), sheet);
            }
        }
    }

    @Test
    public void bottomSheetIsCappedAtEightyFivePercentOfTheAvailableHeight() {
        assertEquals(1700, OverlayDrawerSizing.bottomSheetMaxHeightPx(2000));
        assertEquals(850, OverlayDrawerSizing.bottomSheetMaxHeightPx(1000));
    }

    @Test
    public void bottomSheetCapNeverGoesNegativeAndNeverExceedsTheAvailableHeight() {
        assertEquals(0, OverlayDrawerSizing.bottomSheetMaxHeightPx(0));
        assertEquals(0, OverlayDrawerSizing.bottomSheetMaxHeightPx(-5));
        for (int available = 0; available <= 3000; available += 37) {
            assertTrue(OverlayDrawerSizing.bottomSheetMaxHeightPx(available) <= available);
        }
    }
}
