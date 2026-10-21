package com.termux.x11.controller.winhandler.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PerformanceMetricsSamplerTest {
    private static final double D = 0.0001;

    @Test public void addSampleFillsAllSeries() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        s.addSample(60, 25, 50, 1000, 2000);
        assertEquals(60, s.fps().latest(), D);
        assertEquals(25, s.cpu().latest(), D);
        assertEquals(50, s.memory().latest(), D);
        assertEquals(1000, s.memoryUsedBytes());
        assertEquals(2000, s.memoryTotalBytes());
    }

    @Test public void unavailableFpsIsNanAndOthersContinue() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        s.addSample(Double.NaN, 10, 20, 1, 2);
        s.addSample(-1, 10, 20, 1, 2);
        assertTrue(Double.isNaN(s.fps().latest()));
        assertEquals(2, s.fps().size());
        assertEquals(2, s.cpu().size());
        assertTrue(!s.fps().hasData());
    }

    @Test public void percentagesAreClamped() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        s.addSample(30, 250, -5, 0, 0);
        assertEquals(100, s.cpu().latest(), D);
        assertEquals(0, s.memory().latest(), D);
        s.addSample(30, Double.NaN, 50, 0, 0);
        assertEquals(0, s.cpu().latest(), D);
    }

    @Test public void keepsOnlyLastSixtySeconds() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        for (int i = 0; i < 61; i++) s.addSample(i, i, i, i, 100);
        assertEquals(60, s.cpu().size());
        assertEquals(1, s.cpu().snapshot()[0], D);
        assertEquals(60, s.fps().latest(), D);
    }

    @Test public void clearEmptiesAndNotifies() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        final int[] calls = {0};
        s.setListener(() -> calls[0]++);
        s.addSample(1, 1, 1, 1, 1);
        long v = s.version();
        s.clear();
        assertTrue(s.fps().isEmpty() && s.cpu().isEmpty() && s.memory().isEmpty());
        assertEquals(-1, s.memoryTotalBytes());
        assertTrue(s.version() > v);
        assertEquals(2, calls[0]);
    }

    @Test public void listenerCanBeRemoved() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        final int[] calls = {0};
        s.setListener(() -> calls[0]++);
        s.setListener(null);
        s.addSample(1, 1, 1, 1, 1);
        assertEquals(0, calls[0]);
    }

    @Test public void temperatureSeriesGetsOneValuePerSample() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        s.addSample(60, 25, 50, 1000, 2000, 31.5);
        s.addSample(60, 25, 50, 1000, 2000, 32.0);
        assertEquals(2, s.temperature().size());
        assertEquals(32.0, s.temperature().latest(), D);
        assertEquals(31.5, s.temperature().min(), D);
    }

    @Test public void fiveArgumentSampleHasNoTemperature() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        s.addSample(60, 25, 50, 1000, 2000);
        assertEquals(1, s.temperature().size());
        assertTrue(Double.isNaN(s.temperature().latest()));
        assertTrue(!s.temperature().hasData());
    }

    @Test public void unreadableTemperatureIsNanAndOthersContinue() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        s.addSample(60, 25, 50, 1, 2, Double.NaN);
        s.addSample(60, 25, 50, 1, 2, Double.POSITIVE_INFINITY);
        s.addSample(60, 25, 50, 1, 2, 900);
        s.addSample(60, 25, 50, 1, 2, -273);
        assertEquals(4, s.temperature().size());
        assertTrue(!s.temperature().hasData());
        assertEquals(4, s.fps().size());
        assertEquals(4, s.cpu().size());
        assertEquals(4, s.memory().size());
    }

    @Test public void temperatureKeepsOnlyLastSixtySecondsAndClears() {
        PerformanceMetricsSampler s = new PerformanceMetricsSampler();
        for (int i = 0; i < 61; i++) s.addSample(i, i, i, i, 100, 20 + i % 10);
        assertEquals(60, s.temperature().size());
        s.clear();
        assertTrue(s.temperature().isEmpty());
    }
}
