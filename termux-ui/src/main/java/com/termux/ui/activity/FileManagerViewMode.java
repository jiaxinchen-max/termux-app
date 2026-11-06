package com.termux.ui.activity;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Grid or compact rows for a root collection, saved independently for Wine, Desktop and Files. */
public enum FileManagerViewMode {
    GRID("grid"),
    LIST("list");

    private static final String PREFERENCES = "local_games_settings";

    private final String storageValue;

    FileManagerViewMode(String storageValue) {
        this.storageValue = storageValue;
    }

    public String getStorageValue() { return storageValue; }

    /** The other mode (what the toolbar toggle switches to). */
    @NonNull
    public FileManagerViewMode toggled() {
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
    public static FileManagerViewMode load(@NonNull Context context) {
        return load(context, "file_manager");
    }

    public void save(@NonNull Context context) {
        save(context, "file_manager");
    }

    public static FileManagerViewMode load(Context context, String page) {
        return fromStorageValue(preferences(context).getString(key(page), GRID.storageValue));
    }

    public void save(Context context, String page) {
        preferences(context).edit().putString(key(page), storageValue).apply();
    }

    private static String key(String page) {
        if (!"wine".equals(page) && !"desktop".equals(page) && !"file_manager".equals(page)) {
            throw new IllegalArgumentException("collection_page_unknown");
        }
        return page + "_view_mode";
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES,
            Context.MODE_PRIVATE);
    }
}
