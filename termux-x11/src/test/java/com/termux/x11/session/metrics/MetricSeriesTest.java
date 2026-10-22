package com.termux.x11.session.metrics;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MetricSeriesTest {
    private static final double D = 0.0001;

    @Test public void emptyHasNoData() {
        MetricSeries s = new MetricSeries();
        assertTrue(s.isEmpty());
        assertEquals(0, s.size());
        assertEquals(0, s.snapshot().length);
        assertTrue(Double.isNaN(s.latest()));
        assertTrue(Double.isNaN(s.max()));
        assertTrue(Double.isNaN(s.min()));
        assertFalse(s.hasData());
    }

    @Test public void singlePoint() {
        MetricSeries s = new MetricSeries();
        s.append(42);
        assertEquals(1, s.size());
        assertEquals(42, s.latest(), D);
        assertEquals(42, s.max(), D);
        assertEquals(42, s.min(), D);
        assertArrayEquals(new double[]{42}, s.snapshot(), D);
    }

    @Test public void fullSixtyKeepsOrder() {
        MetricSeries s = new MetricSeries();
        for (int i = 0; i < 60; i++) s.append(i);
        assertEquals(60, s.size());
        double[] snap = s.snapshot();
        assertEquals(0, snap[0], D);
        assertEquals(59, snap[59], D);
        assertEquals(59, s.latest(), D);
    }

    @Test public void overflowDropsOldest() {
        MetricSeries s = new MetricSeries();
        for (int i = 0; i < 75; i++) s.append(i);
        assertEquals(60, s.size());
        double[] snap = s.snapshot();
        assertEquals(15, snap[0], D);
        assertEquals(74, snap[59], D);
        for (int i = 1; i < snap.length; i++) assertEquals(snap[i - 1] + 1, snap[i], D);
        assertEquals(15, s.min(), D);
        assertEquals(74, s.max(), D);
    }

    @Test public void overflowManyWraps() {
        MetricSeries s = new MetricSeries();
        for (int i = 0; i < 1000; i++) s.append(i);
        assertEquals(940, s.snapshot()[0], D);
        assertEquals(999, s.latest(), D);
    }

    @Test public void nanIsKeptButIgnoredByRange() {
        MetricSeries s = new MetricSeries();
        s.append(10);
        s.append(Double.NaN);
        s.append(30);
        s.append(Double.NaN);
        assertEquals(4, s.size());
        assertTrue(Double.isNaN(s.latest()));
        assertEquals(30, s.max(), D);
        assertEquals(10, s.min(), D);
        assertTrue(s.hasData());
    }

    @Test public void onlyNanHasNoData() {
        MetricSeries s = new MetricSeries();
        s.append(Double.NaN);
        s.append(Double.NaN);
        assertFalse(s.hasData());
        assertTrue(Double.isNaN(s.max()));
    }

    @Test public void clearResets() {
        MetricSeries s = new MetricSeries();
        for (int i = 0; i < 70; i++) s.append(i);
        s.clear();
        assertTrue(s.isEmpty());
        s.append(5);
        assertArrayEquals(new double[]{5}, s.snapshot(), D);
    }
}
