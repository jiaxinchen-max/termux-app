package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameControllerListSizingTest {

    @Test
    public void theBoxShowsExactlyFourRows() {
        assertEquals(4, GameControllerListSizing.VISIBLE_ROWS);
    }

    @Test
    public void heightIsPaddingPlusFourRowsWithTheirGaps() {
        // 48 dp rows, 8 dp gaps, 8 dp padding: 8 + 4 * (48 + 8) = 232.
        assertEquals(232, GameControllerListSizing.viewportHeightPx(48, 8, 8));
    }

    @Test
    public void heightScalesLinearlyWithTheRowHeight() {
        int base = GameControllerListSizing.viewportHeightPx(100, 20, 20);
        int taller = GameControllerListSizing.viewportHeightPx(110, 20, 20);
        assertEquals(4 * 10, taller - base);
    }

    @Test
    public void heightWithoutGapsOrPaddingIsFourRows() {
        assertEquals(4 * 60, GameControllerListSizing.viewportHeightPx(60, 0, 0));
    }

    @Test
    public void onlyMoreThanFourProfilesScroll() {
        assertFalse(GameControllerListSizing.scrolls(0));
        assertFalse(GameControllerListSizing.scrolls(3));
        assertFalse(GameControllerListSizing.scrolls(4));
        assertTrue(GameControllerListSizing.scrolls(5));
        assertTrue(GameControllerListSizing.scrolls(40));
    }
}
