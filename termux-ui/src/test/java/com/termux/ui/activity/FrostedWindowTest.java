package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FrostedWindowTest {

    @Test
    public void blurIsOnlyUsedFromApi31() {
        assertFalse(FrostedWindow.isBlurSupported(21));
        assertFalse(FrostedWindow.isBlurSupported(30));
        assertTrue(FrostedWindow.isBlurSupported(31));
        assertTrue(FrostedWindow.isBlurSupported(34));
    }

    @Test
    public void blurRadiusScalesWithDensityAndStaysInDesignRange() {
        assertEquals(22, FrostedWindow.blurRadiusPx(1f));
        assertEquals(66, FrostedWindow.blurRadiusPx(3f));
        assertTrue(FrostedWindow.BLUR_RADIUS_DP >= 20 && FrostedWindow.BLUR_RADIUS_DP <= 24);
    }

    @Test
    public void dimIsLighterWhenTheWindowIsBlurred() {
        assertTrue(FrostedWindow.dimAmount(31) < FrostedWindow.dimAmount(30));
    }

    @Test
    public void cornerRadiiStayWithinTheSmallDesignTokens() {
        assertEquals(6, FrostedWindow.ROW_RADIUS_DP);
        assertEquals(8, FrostedWindow.PANEL_RADIUS_DP);
    }
}
