package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalGamesBackNavigationTest {

    @Test
    public void settingsRootGoesBackToTheLibrary() {
        assertEquals(LocalGamesBackNavigation.Action.SHOW_LIBRARY,
            LocalGamesBackNavigation.resolve(false, true, true));
    }

    @Test
    public void settingsSubPageGoesUpOneLevelFirst() {
        assertEquals(LocalGamesBackNavigation.Action.GO_UP_IN_SETTINGS,
            LocalGamesBackNavigation.resolve(false, true, false));
    }

    @Test
    public void otherNonLibraryTabsGoBackToTheLibrary() {
        assertEquals(LocalGamesBackNavigation.Action.SHOW_LIBRARY,
            LocalGamesBackNavigation.resolve(false, false, true));
    }

    @Test
    public void libraryLeavesTheScreen() {
        assertEquals(LocalGamesBackNavigation.Action.LEAVE_SCREEN,
            LocalGamesBackNavigation.resolve(true, false, true));
    }

    @Test
    public void systemBackIsInterceptedExceptOnTheLibrary() {
        assertTrue(LocalGamesBackNavigation.interceptsSystemBack(false, true, true));
        assertTrue(LocalGamesBackNavigation.interceptsSystemBack(false, true, false));
        assertTrue(LocalGamesBackNavigation.interceptsSystemBack(false, false, true));
        assertFalse(LocalGamesBackNavigation.interceptsSystemBack(true, false, true));
    }
}
