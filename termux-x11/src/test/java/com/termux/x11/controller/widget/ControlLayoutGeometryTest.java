package com.termux.x11.controller.widget;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.x11.controller.inputcontrols.ControlsOrientation;

import org.junit.Test;

public class ControlLayoutGeometryTest {
    private static final float EPS = 0.001f;

    @Test
    public void landscapeProfileOnLandscapeScreenIsNotScaled() {
        ControlLayoutGeometry geometry = ControlLayoutGeometry.compute(ControlsOrientation.LANDSCAPE, 2400, 1080);
        assertEquals(2400, geometry.layoutWidth);
        assertEquals(1080, geometry.layoutHeight);
        assertEquals(24, geometry.snappingSize);
        assertEquals(1.0f, geometry.scale, 0f);
        assertEquals(0f, geometry.offsetX, EPS);
        assertEquals(0f, geometry.offsetY, EPS);
    }

    @Test
    public void portraitProfileOnPortraitScreenIsNotScaled() {
        ControlLayoutGeometry geometry = ControlLayoutGeometry.compute(ControlsOrientation.PORTRAIT, 1080, 2400);
        assertEquals(1080, geometry.layoutWidth);
        assertEquals(2400, geometry.layoutHeight);
        assertEquals(24, geometry.snappingSize);
        assertEquals(1.0f, geometry.scale, 0f);
        assertEquals(0f, geometry.offsetX, EPS);
        assertEquals(0f, geometry.offsetY, EPS);
    }

    @Test
    public void matchingOrientationNeverScalesEvenWhenTheEdgeIsNotOnTheGrid() {
        ControlLayoutGeometry geometry = ControlLayoutGeometry.compute(ControlsOrientation.LANDSCAPE, 2412, 1083);
        assertEquals(24, geometry.snappingSize);
        assertEquals(2400, geometry.layoutWidth);
        assertEquals(1080, geometry.layoutHeight);
        assertEquals(1.0f, geometry.scale, 0f);
        assertEquals(6f, geometry.offsetX, EPS);
        assertEquals(1.5f, geometry.offsetY, EPS);
    }

    @Test
    public void landscapeProfileOnPortraitScreenIsScaledToFitAndCentered() {
        ControlLayoutGeometry geometry = ControlLayoutGeometry.compute(ControlsOrientation.LANDSCAPE, 1080, 2400);
        assertEquals(2400, geometry.layoutWidth);
        assertEquals(1080, geometry.layoutHeight);
        assertEquals(0.45f, geometry.scale, EPS);
        assertEquals(0f, geometry.offsetX, EPS);
        assertEquals((2400 - 1080 * 0.45f) / 2, geometry.offsetY, EPS);
    }

    @Test
    public void portraitProfileOnLandscapeScreenIsScaledToFitAndCentered() {
        ControlLayoutGeometry geometry = ControlLayoutGeometry.compute(ControlsOrientation.PORTRAIT, 2400, 1080);
        assertEquals(1080, geometry.layoutWidth);
        assertEquals(2400, geometry.layoutHeight);
        assertEquals(0.45f, geometry.scale, EPS);
        assertEquals((2400 - 1080 * 0.45f) / 2, geometry.offsetX, EPS);
        assertEquals(0f, geometry.offsetY, EPS);
    }

    @Test
    public void snappingGridDoesNotDependOnTheOrientation() {
        int landscape = ControlLayoutGeometry.compute(ControlsOrientation.LANDSCAPE, 2400, 1080).snappingSize;
        assertEquals(landscape, ControlLayoutGeometry.compute(ControlsOrientation.PORTRAIT, 2400, 1080).snappingSize);
        assertEquals(landscape, ControlLayoutGeometry.compute(ControlsOrientation.PORTRAIT, 1080, 2400).snappingSize);
    }

    @Test
    public void squareScreenCountsAsLandscape() {
        assertTrue(ControlLayoutGeometry.isUnscaled(ControlsOrientation.LANDSCAPE, 1000, 1000));
        assertFalse(ControlLayoutGeometry.isUnscaled(ControlsOrientation.PORTRAIT, 1000, 1000));
    }

    @Test
    public void viewAndLayoutCoordinatesConvertBothWays() {
        ControlLayoutGeometry geometry = ControlLayoutGeometry.compute(ControlsOrientation.LANDSCAPE, 1080, 2400);
        float x = geometry.toViewX(1200f);
        float y = geometry.toViewY(540f);
        assertEquals(1200f, geometry.toLayoutX(x), EPS);
        assertEquals(540f, geometry.toLayoutY(y), EPS);
        assertEquals(540f, x, EPS);
        assertEquals(1200f, y, EPS);
    }

    @Test
    public void scaledLayoutStaysInsideTheView() {
        int[][] views = {{1080, 2400}, {2400, 1080}, {720, 1600}, {1600, 720}, {1000, 1000}};
        for (int[] view : views) {
            for (ControlsOrientation orientation : ControlsOrientation.values()) {
                ControlLayoutGeometry geometry = ControlLayoutGeometry.compute(orientation, view[0], view[1]);
                assertTrue(geometry.offsetX >= -EPS && geometry.offsetY >= -EPS);
                assertTrue(geometry.toViewX(geometry.layoutWidth) <= view[0] + EPS);
                assertTrue(geometry.toViewY(geometry.layoutHeight) <= view[1] + EPS);
            }
        }
    }
}
