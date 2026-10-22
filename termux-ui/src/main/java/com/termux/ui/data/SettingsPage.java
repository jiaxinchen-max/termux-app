package com.termux.ui.data;

import androidx.annotation.Nullable;

/** The menu level currently shown on the Settings page. The first level lists the settings; the two
 *  environment entries open a second-level menu of their own. */
public enum SettingsPage {
    ROOT(null),
    NATIVE(ROOT),
    PROOT(ROOT);

    @Nullable private final SettingsPage parent;

    SettingsPage(@Nullable SettingsPage parent) {
        this.parent = parent;
    }

    /** The level that back returns to, or null on the first level. */
    @Nullable
    public SettingsPage parent() { return parent; }

    public boolean isRoot() { return parent == null; }

    /** Restores a saved ordinal; anything unknown falls back to the first level. */
    public static SettingsPage fromOrdinal(int ordinal) {
        SettingsPage[] pages = values();
        return ordinal >= 0 && ordinal < pages.length ? pages[ordinal] : ROOT;
    }
}
