package com.termux.x11.controller.inputcontrols;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ControlsOrientationTest {
    @Test
    public void missingOrUnknownStorageValueIsLandscape() {
        assertEquals(ControlsOrientation.LANDSCAPE, ControlsOrientation.fromStorage(null));
        assertEquals(ControlsOrientation.LANDSCAPE, ControlsOrientation.fromStorage(""));
        assertEquals(ControlsOrientation.LANDSCAPE, ControlsOrientation.fromStorage("diagonal"));
    }

    @Test
    public void storageValueRoundTrips() {
        for (ControlsOrientation orientation : ControlsOrientation.values()) {
            assertEquals(orientation, ControlsOrientation.fromStorage(orientation.storageValue()));
        }
        assertEquals("landscape", ControlsOrientation.LANDSCAPE.storageValue());
        assertEquals("portrait", ControlsOrientation.PORTRAIT.storageValue());
    }

    @Test
    public void sizeDecidesOrientationAndSquareIsLandscape() {
        assertEquals(ControlsOrientation.LANDSCAPE, ControlsOrientation.ofSize(2400, 1080));
        assertEquals(ControlsOrientation.PORTRAIT, ControlsOrientation.ofSize(1080, 2400));
        assertEquals(ControlsOrientation.LANDSCAPE, ControlsOrientation.ofSize(1080, 1080));
    }

    @Test
    public void oppositeSwitchesOrientation() {
        assertEquals(ControlsOrientation.PORTRAIT, ControlsOrientation.LANDSCAPE.opposite());
        assertEquals(ControlsOrientation.LANDSCAPE, ControlsOrientation.PORTRAIT.opposite());
    }
}
