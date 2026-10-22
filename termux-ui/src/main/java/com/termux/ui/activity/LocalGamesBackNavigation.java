package com.termux.ui.activity;

/**
 * What "back" does on the library screen, shared by the system BACK callback and the toolbar arrow
 * so both always agree: a second-level Settings page goes up one level, every other non-Library
 * page returns to the Library tab, and only the Library tab leaves the screen.
 */
final class LocalGamesBackNavigation {

    enum Action {
        /** Show the parent of the current second-level Settings page. */
        GO_UP_IN_SETTINGS,
        /** Switch to the Library tab. */
        SHOW_LIBRARY,
        /** Nothing to navigate: the Library tab is showing and back leaves the screen. */
        LEAVE_SCREEN
    }

    private LocalGamesBackNavigation() {}

    static Action resolve(boolean libraryTab, boolean settingsTab, boolean settingsRoot) {
        if (settingsTab && !settingsRoot) return Action.GO_UP_IN_SETTINGS;
        if (!libraryTab) return Action.SHOW_LIBRARY;
        return Action.LEAVE_SCREEN;
    }

    /** Whether the system BACK callback must intercept; on the Library tab the default applies. */
    static boolean interceptsSystemBack(boolean libraryTab, boolean settingsTab, boolean settingsRoot) {
        return resolve(libraryTab, settingsTab, settingsRoot) != Action.LEAVE_SCREEN;
    }

    /** The navigation chrome of the library screen: a bottom bar in portrait, a row of icon tabs
     *  at the top of the content in landscape. */
    enum NavigationStyle {
        BOTTOM_BAR,
        TOP_TABS
    }

    /** What the navigation highlights. File manager is an entry, never a highlighted tab. */
    enum Highlight {
        LIBRARY,
        SETTINGS
    }

    /** Both orientations offer the same three destinations; only the chrome differs. */
    static NavigationStyle navigationStyle(boolean landscape) {
        return landscape ? NavigationStyle.TOP_TABS : NavigationStyle.BOTTOM_BAR;
    }

    /** The navigation (bottom bar in portrait, top tabs in landscape) is shown on the Library and Settings
     *  tabs and hidden on the game component tab, whatever the orientation. */
    static boolean navigationVisible(boolean libraryTab, boolean settingsTab) {
        return libraryTab || settingsTab;
    }

    /** Which item is highlighted: Settings on the Settings tab, otherwise Library (the component
     *  tab is reached from a game and keeps the Library highlighted). Identical in both orientations. */
    static Highlight highlight(boolean settingsTab) {
        return settingsTab ? Highlight.SETTINGS : Highlight.LIBRARY;
    }

    /** The landscape top tab row shows on the Library and Settings tabs of a landscape window only. */
    static boolean topTabsVisible(boolean landscape, boolean libraryTab, boolean settingsTab) {
        return navigationStyle(landscape) == NavigationStyle.TOP_TABS &&
            navigationVisible(libraryTab, settingsTab);
    }

    /** The bottom bar is the portrait counterpart: exactly one of the two chromes is ever shown. */
    static boolean bottomBarVisible(boolean landscape, boolean libraryTab, boolean settingsTab) {
        return navigationStyle(landscape) == NavigationStyle.BOTTOM_BAR &&
            navigationVisible(libraryTab, settingsTab);
    }

    /**
     * The File manager entry opens another screen; before leaving, any non-Library tab returns to
     * the Library so coming back from the file manager lands on the Library.
     */
    static boolean showsLibraryBeforeFileManager(boolean libraryTab) {
        return !libraryTab;
    }
}
