package com.termux.ui.activity;

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

    @Test
    public void navigationShowsOnLibraryAndSettingsOnly() {
        assertTrue(LocalGamesBackNavigation.navigationVisible(true, false));
        assertTrue(LocalGamesBackNavigation.navigationVisible(false, true));
        assertFalse(LocalGamesBackNavigation.navigationVisible(false, false));
    }

    @Test
    public void portraitUsesTheBottomBarAndLandscapeTheTopTabs() {
        assertEquals(LocalGamesBackNavigation.NavigationStyle.BOTTOM_BAR,
            LocalGamesBackNavigation.navigationStyle(false));
        assertEquals(LocalGamesBackNavigation.NavigationStyle.TOP_TABS,
            LocalGamesBackNavigation.navigationStyle(true));
    }

    @Test
    public void topTabsShowInLandscapeOnLibraryAndSettingsOnly() {
        assertTrue(LocalGamesBackNavigation.topTabsVisible(true, true, false));
        assertTrue(LocalGamesBackNavigation.topTabsVisible(true, false, true));
        // The game component sub-page has no navigation at all.
        assertFalse(LocalGamesBackNavigation.topTabsVisible(true, false, false));
        // Portrait never shows the top tabs.
        assertFalse(LocalGamesBackNavigation.topTabsVisible(false, true, false));
        assertFalse(LocalGamesBackNavigation.topTabsVisible(false, false, true));
    }

    @Test
    public void bottomBarAndTopTabsAreMutuallyExclusive() {
        for (int mask = 0; mask < 8; mask++) {
            boolean landscape = (mask & 1) != 0;
            boolean library = (mask & 2) != 0;
            boolean settings = (mask & 4) != 0;
            boolean top = LocalGamesBackNavigation.topTabsVisible(landscape, library, settings);
            boolean bottom = LocalGamesBackNavigation.bottomBarVisible(landscape, library, settings);
            assertFalse(top && bottom);
            assertEquals(LocalGamesBackNavigation.navigationVisible(library, settings), top || bottom);
        }
        assertTrue(LocalGamesBackNavigation.bottomBarVisible(false, true, false));
        assertFalse(LocalGamesBackNavigation.bottomBarVisible(true, true, false));
    }

    @Test
    public void highlightFollowsTheTabTheSameInBothOrientations() {
        assertEquals(LocalGamesBackNavigation.Highlight.SETTINGS,
            LocalGamesBackNavigation.highlight(true));
        assertEquals(LocalGamesBackNavigation.Highlight.LIBRARY,
            LocalGamesBackNavigation.highlight(false));
    }

    @Test
    public void fileManagerEntryReturnsNonLibraryTabsToTheLibraryFirst() {
        assertTrue(LocalGamesBackNavigation.showsLibraryBeforeFileManager(false));
        assertFalse(LocalGamesBackNavigation.showsLibraryBeforeFileManager(true));
    }

    @Test
    public void topTabBackStepsAreTheSameAsTheBottomBar() {
        // Back resolution takes no orientation: sub-page -> Settings -> Library -> exit everywhere.
        assertEquals(LocalGamesBackNavigation.Action.GO_UP_IN_SETTINGS,
            LocalGamesBackNavigation.resolve(false, true, false));
        assertTrue(LocalGamesBackNavigation.interceptsSystemBack(false, true, false));
        assertFalse(LocalGamesBackNavigation.interceptsSystemBack(true, false, true));
    }

    @Test
    public void settingsTabBackStepsRootThenLibraryThenLeaves() {
        assertEquals(LocalGamesBackNavigation.Action.GO_UP_IN_SETTINGS,
            LocalGamesBackNavigation.resolve(false, true, false));
        assertEquals(LocalGamesBackNavigation.Action.SHOW_LIBRARY,
            LocalGamesBackNavigation.resolve(false, true, true));
        assertEquals(LocalGamesBackNavigation.Action.LEAVE_SCREEN,
            LocalGamesBackNavigation.resolve(true, false, true));
    }
}
