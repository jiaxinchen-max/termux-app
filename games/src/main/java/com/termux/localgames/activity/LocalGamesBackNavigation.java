package com.termux.localgames.activity;

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
}
