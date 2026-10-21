package com.termux.x11.controller.winhandler.metrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TemperatureReaderTest {
    private static final double D = 0.0001;

    @Test public void batteryTenthsConvertToCelsius() {
        assertEquals(31.5, TemperatureReader.fromBatteryTenths(315), D);
        assertEquals(25.0, TemperatureReader.fromBatteryTenths(250), D);
        assertEquals(-5.0, TemperatureReader.fromBatteryTenths(-50), D);
    }

    @Test public void missingOrSentinelReadingIsNan() {
        assertTrue(Double.isNaN(TemperatureReader.fromBatteryTenths(Integer.MIN_VALUE)));
        assertTrue(Double.isNaN(TemperatureReader.fromBatteryTenths(0)));
    }

    @Test public void implausibleReadingIsNan() {
        assertTrue(Double.isNaN(TemperatureReader.fromBatteryTenths(5000)));
        assertTrue(Double.isNaN(TemperatureReader.fromBatteryTenths(-2730)));
        assertEquals(150.0, TemperatureReader.fromBatteryTenths(1500), D);
        assertEquals(-40.0, TemperatureReader.fromBatteryTenths(-400), D);
    }

    @Test public void sanitizeRejectsNonFiniteValues() {
        assertTrue(Double.isNaN(TemperatureReader.sanitize(Double.NaN)));
        assertTrue(Double.isNaN(TemperatureReader.sanitize(Double.POSITIVE_INFINITY)));
        assertTrue(Double.isNaN(TemperatureReader.sanitize(Double.NEGATIVE_INFINITY)));
        assertEquals(42.0, TemperatureReader.sanitize(42.0), D);
    }
}
