package com.termux.x11;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class X11RefreshRatePolicyTest {
    @Test public void explicitDesktopRateSurvivesHostDisplayAndSurfaceChanges() {
        X11RefreshRatePolicy policy = new X11RefreshRatePolicy();
        policy.setExplicit(30);
        // A resized/recreated Surface, external display or disconnected Display must
        // never replace the desktop's requested rate with the physical monitor rate.
        for (float hostRate : new float[] {60, 120, 59.94f, 30, 144})
            assertEquals(30, policy.resolve(hostRate));
        policy.setExplicit(90);
        assertEquals(90, policy.resolve(60));
    }

    @Test public void legacyTerminalAndGameSessionsRetainDisplayDrivenRates() {
        X11RefreshRatePolicy policy = new X11RefreshRatePolicy();
        assertEquals(60, policy.resolve(60));
        assertEquals(119, policy.resolve(119.88f));
        assertEquals(144, policy.resolve(144));
        assertEquals(30, policy.resolve(30));
        policy.setExplicit(30);
        policy.setExplicit(null);
        assertEquals(120, policy.resolve(120));
    }

    @Test public void invalidExplicitRatesAreRejectedWithoutSelectingAnotherRate() {
        X11RefreshRatePolicy policy = new X11RefreshRatePolicy();
        policy.setExplicit(30);
        for (int invalid : new int[] {0, -1, 121, Integer.MAX_VALUE}) {
            try {
                policy.setExplicit(invalid);
                fail("invalid explicit rate was accepted: " + invalid);
            } catch (IllegalArgumentException expected) { }
            assertEquals(30, policy.resolve(60));
        }
        policy.setExplicit(1);
        assertEquals(1, policy.resolve(60));
        policy.setExplicit(120);
        assertEquals(120, policy.resolve(60));
    }
}
