package com.termux.ui.platform;

import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.termux.ui.R;

/** UI-only appearance. An explicit local mode keeps it independent of the host terminal theme. */
public enum UiThemePreference {
    AUTO("auto", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.local_games_theme_auto),
    LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, R.string.local_games_theme_light),
    DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, R.string.local_games_theme_dark);

    private final String storageValue;
    private final int nightMode;
    private final int label;

    UiThemePreference(String storageValue, int nightMode, int label) {
        this.storageValue = storageValue;
        this.nightMode = nightMode;
        this.label = label;
    }

    public String getStorageValue() { return storageValue; }
    public int getNightMode() { return nightMode; }
    public int getLabelRes() { return label; }

    public static UiThemePreference fromStorageValue(String value) {
        for (UiThemePreference choice : values()) {
            if (choice.storageValue.equals(value)) return choice;
        }
        throw new IllegalArgumentException("unknown_ui_theme:" + value);
    }

    public static UiThemePreference load(Context context) {
        return fromStorageValue(context.getApplicationContext()
            .getSharedPreferences("local_games_settings", Context.MODE_PRIVATE)
            .getString("ui_theme", AUTO.storageValue));
    }

    public void save(Context context) {
        context.getApplicationContext().getSharedPreferences("local_games_settings", Context.MODE_PRIVATE)
            .edit().putString("ui_theme", storageValue).apply();
    }

    /** Apply before creating views, and when a retained page resumes after a setting change. */
    public static void applyTo(AppCompatActivity activity) {
        int mode = load(activity).getNightMode();
        if (activity.getDelegate().getLocalNightMode() != mode) {
            activity.getDelegate().setLocalNightMode(mode);
        }
    }
}
