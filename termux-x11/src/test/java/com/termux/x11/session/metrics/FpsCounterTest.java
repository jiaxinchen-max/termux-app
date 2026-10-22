package com.termux.x11.session.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FpsCounterTest {
    private static final long SEC = 1_000_000_000L;

    @Test public void firstReadingHasNoRate() {
        assertTrue(Double.isNaN(new FpsCounter().update(100, SEC)));
    }

    @Test public void differencesPerSecond() {
        FpsCounter c = new FpsCounter();
        c.update(100, SEC);
        assertEquals(60d, c.update(160, 2 * SEC), 0.0001);
        assertEquals(30d, c.update(190, 3 * SEC), 0.0001);
    }

    @Test public void scalesByActualElapsedTime() {
        FpsCounter c = new FpsCounter();
        c.update(0, 0);
        assertEquals(60d, c.update(90, 3 * SEC / 2), 0.0001);
    }

    @Test public void idleRendererIsZeroNotMissing() {
        FpsCounter c = new FpsCounter();
        c.update(500, SEC);
        assertEquals(0d, c.update(500, 2 * SEC), 0.0001);
    }

    @Test public void unavailableSourceGivesNanAndRestarts() {
        FpsCounter c = new FpsCounter();
        c.update(10, SEC);
        assertTrue(Double.isNaN(c.update(-1, 2 * SEC)));
        assertTrue(Double.isNaN(c.update(50, 3 * SEC)));
        assertEquals(10d, c.update(60, 4 * SEC), 0.0001);
    }

    @Test public void counterGoingBackwardsIsNanThenRecovers() {
        FpsCounter c = new FpsCounter();
        c.update(1000, SEC);
        assertTrue(Double.isNaN(c.update(5, 2 * SEC)));
        assertEquals(20d, c.update(25, 3 * SEC), 0.0001);
    }

    @Test public void resetDropsTheGap() {
        FpsCounter c = new FpsCounter();
        c.update(10, SEC);
        c.reset();
        assertTrue(Double.isNaN(c.update(1000, 100 * SEC)));
    }

    @Test public void nonAdvancingClockIsNan() {
        FpsCounter c = new FpsCounter();
        c.update(10, SEC);
        assertTrue(Double.isNaN(c.update(20, SEC)));
    }
}
