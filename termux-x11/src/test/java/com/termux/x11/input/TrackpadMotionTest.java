package com.termux.x11.input;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** Check visible cursor travel, rather than just comparing desktop pixel counts. */
public class TrackpadMotionTest {
    private RenderData display(int desktopWidth, int desktopHeight, int viewWidth, int viewHeight,
                               int viewportWidth, int viewportHeight) {
        RenderData data = new RenderData();
        data.screenWidth = desktopWidth;
        data.screenHeight = desktopHeight;
        data.imageWidth = viewWidth;
        data.imageHeight = viewHeight;
        data.viewportWidth = viewportWidth;
        data.viewportHeight = viewportHeight;
        return data;
    }

    private void assertVisibleTravel(RenderData data, float dx, float dy) {
        assertEquals(dx, data.mapScreenDeltaX(dx) * data.viewportWidth / data.screenWidth, 0.0001f);
        assertEquals(dy, data.mapScreenDeltaY(dy) * data.viewportHeight / data.screenHeight, 0.0001f);
    }

    @Test public void portraitLetterboxDoesNotSlowVerticalMovement() {
        RenderData data = display(1920, 1080, 1080, 2400, 1080, 608);
        assertVisibleTravel(data, 100, 100);
        assertVisibleTravel(data, -25, -25);
    }

    @Test public void landscapeLetterboxDoesNotSlowHorizontalMovement() {
        assertVisibleTravel(display(1280, 1024, 2400, 1080, 1350, 1080), 100, 100);
    }

    @Test public void stretchedDesktopCompensatesEachRenderedAxis() {
        assertVisibleTravel(display(1280, 720, 2400, 1080, 2400, 1080), 100, 100);
    }

    @Test public void nativeResolutionStillHandlesInsetsAndZoom() {
        RenderData data = display(2400, 1080, 2400, 1080, 2200, 990);
        assertVisibleTravel(data, 100, 100);
        data.viewportWidth = 4400;
        data.viewportHeight = 1980;
        data.viewportOffsetX = -700;
        data.viewportOffsetY = 42;
        assertVisibleTravel(data, 0.125f, -0.25f);
    }

    @Test public void rotationAndResolutionChangeUseNewViewportImmediately() {
        RenderData data = display(1920, 1080, 1080, 2400, 1080, 608);
        assertVisibleTravel(data, 40, 70);
        data.screenWidth = 1280;
        data.screenHeight = 1024;
        data.viewportWidth = 1350;
        data.viewportHeight = 1080;
        assertVisibleTravel(data, 40, 70);
    }

    @Test public void fallbackBeforeViewportAndDesktopSizesAreKnownIsFinite() {
        RenderData data = new RenderData();
        assertEquals(0.25f, data.mapScreenDeltaX(0.25f), 0);
        assertEquals(-0.25f, data.mapScreenDeltaY(-0.25f), 0);
        data.screenWidth = 1920;
        data.screenHeight = 1080;
        data.imageWidth = 960;
        data.imageHeight = 540;
        assertEquals(0.5f, data.mapScreenDeltaX(0.25f), 0);
        assertEquals(-0.5f, data.mapScreenDeltaY(-0.25f), 0);
    }
}
