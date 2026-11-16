package com.termux.ui.desktop.activity;

import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopCardLayoutTest {
    @Test public void compactCardsAdaptWithoutStretchingToFillWindow() {
        assertEquals(2, DesktopCardLayout.columns(360, 1f));
        assertEquals(6, DesktopCardLayout.columns(840, 1f));
        assertEquals(128, DesktopCardLayout.widthDp(360, 1f));
        assertEquals(128, DesktopCardLayout.widthDp(840, 1f));
        assertEquals(1, DesktopCardLayout.columns(240, 2f));
        for (int width : new int[]{96, 160, 240, 360, 600, 840}) {
            for (float font : new float[]{1f, 1.3f, 2f}) {
                int card = DesktopCardLayout.widthDp(width, font);
                int columns = DesktopCardLayout.columns(width, font);
                assertTrue(columns * (card + 6) <= width);
            }
        }
    }
}
