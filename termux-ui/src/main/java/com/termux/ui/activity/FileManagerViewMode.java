package com.termux.ui.activity;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** How the file manager lays out a folder: compact icon tiles or one compact row per entry. The
 *  choice is kept in a stored preference so it survives restarts. */
enum FileManagerViewMode {
    GRID("grid"),
    LIST("list");

    private static final String PREFERENCES = "local_games_settings";
    private static final String KEY = "file_manager_view_mode";

    private final String storageValue;

    FileManagerViewMode(String storageValue) {
        this.storageValue = storageValue;
    }

    String getStorageValue() { return storageValue; }

    /** The other mode (what the toolbar toggle switches to). */
    @NonNull
    FileManagerViewMode toggled() {
        return this == GRID ? LIST : GRID;
    }

    /** Unknown or missing values fall back to {@link #GRID}, the original presentation. */
    @NonNull
    static FileManagerViewMode fromStorageValue(@Nullable String value) {
        for (FileManagerViewMode mode : values()) {
            if (mode.storageValue.equals(value)) return mode;
        }
        return GRID;
    }

    @NonNull
    static FileManagerViewMode load(@NonNull Context context) {
        return fromStorageValue(preferences(context).getString(KEY, GRID.storageValue));
    }

    void save(@NonNull Context context) {
        preferences(context).edit().putString(KEY, storageValue).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES,
            Context.MODE_PRIVATE);
    }
}
