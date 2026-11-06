package com.termux.ui.data;

import androidx.annotation.Nullable;

/** Settings > Core components > Native / Proot. */
public enum SettingsPage {
    ROOT(null),
    CORE_COMPONENTS(ROOT),
    NATIVE(CORE_COMPONENTS),
    PROOT(CORE_COMPONENTS);

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
