package com.termux.x11.controller.winhandler.metrics;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ChartGeometryTest {
    private static final float F = 0.001f;

    @Test public void percentAxisIsFixed() {
        assertEquals(100d, ChartGeometry.percentAxisMax(), 0);
    }

    @Test public void fpsAxisSteps() {
        assertEquals(30, ChartGeometry.fpsAxisMax(Double.NaN), 0);
        assertEquals(30, ChartGeometry.fpsAxisMax(0), 0);
        assertEquals(30, ChartGeometry.fpsAxisMax(12), 0);
        assertEquals(30, ChartGeometry.fpsAxisMax(30), 0);
        assertEquals(60, ChartGeometry.fpsAxisMax(31), 0);
        assertEquals(60, ChartGeometry.fpsAxisMax(59.9), 0);
        assertEquals(144, ChartGeometry.fpsAxisMax(130), 0);
        assertEquals(360, ChartGeometry.fpsAxisMax(300), 0);
        assertEquals(420, ChartGeometry.fpsAxisMax(361), 0);
    }

    @Test public void temperatureAxisSteps() {
        assertEquals(50, ChartGeometry.temperatureAxisMax(Double.NaN), 0);
        assertEquals(50, ChartGeometry.temperatureAxisMax(0), 0);
        assertEquals(50, ChartGeometry.temperatureAxisMax(-3), 0);
        assertEquals(50, ChartGeometry.temperatureAxisMax(31.5), 0);
        assertEquals(50, ChartGeometry.temperatureAxisMax(50), 0);
        assertEquals(60, ChartGeometry.temperatureAxisMax(50.1), 0);
        assertEquals(80, ChartGeometry.temperatureAxisMax(61), 0);
        assertEquals(100, ChartGeometry.temperatureAxisMax(99), 0);
        assertEquals(120, ChartGeometry.temperatureAxisMax(120), 0);
        assertEquals(140, ChartGeometry.temperatureAxisMax(121), 0);
    }

    @Test public void fullWindowSpansWidth() {
        assertEquals(10f, ChartGeometry.xFor(0, 60, 10f, 590f), F);
        assertEquals(600f, ChartGeometry.xFor(59, 60, 10f, 590f), F);
    }

    @Test public void shortHistoryIsRightAligned() {
        assertEquals(600f, ChartGeometry.xFor(0, 1, 10f, 590f), F);
        float first = ChartGeometry.xFor(0, 2, 0f, 59f);
        float second = ChartGeometry.xFor(1, 2, 0f, 59f);
        assertEquals(58f, first, F);
        assertEquals(59f, second, F);
    }

    @Test public void yMapsAndClamps() {
        assertEquals(110f, ChartGeometry.yFor(0, 100, 10f, 100f), F);
        assertEquals(10f, ChartGeometry.yFor(100, 100, 10f, 100f), F);
        assertEquals(60f, ChartGeometry.yFor(50, 100, 10f, 100f), F);
        assertEquals(10f, ChartGeometry.yFor(500, 100, 10f, 100f), F);
        assertEquals(110f, ChartGeometry.yFor(-5, 100, 10f, 100f), F);
        // A non-positive axis is treated as 1, so a larger value pins to the top.
        assertEquals(10f, ChartGeometry.yFor(5, 0, 10f, 100f), F);
    }
}
