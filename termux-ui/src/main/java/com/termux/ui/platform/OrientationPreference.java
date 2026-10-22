package com.termux.ui.platform;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import androidx.annotation.NonNull;

/** The orientation choice from Settings: portrait, landscape, or whatever the device's auto-rotate
 *  setting says. It applies to the library screen (via {@link #getRequestedOrientation()}) and to
 *  the game session (via {@code GameSessionOrientation}). Kept as a stored preference so it
 *  survives restarts. */
public enum OrientationPreference {
    VERTICAL("vertical", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
    HORIZONTAL("horizontal", ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    AUTO("auto", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

    private static final String PREFERENCES = "local_games_settings";
    private static final String KEY = "orientation";

    private final String storageValue;
    private final int requestedOrientation;

    OrientationPreference(String storageValue, int requestedOrientation) {
        this.storageValue = storageValue;
        this.requestedOrientation = requestedOrientation;
    }

    public String getStorageValue() { return storageValue; }

    /** Value for {@code Activity.setRequestedOrientation}. */
    public int getRequestedOrientation() { return requestedOrientation; }

    /** Unknown or missing values fall back to {@link #AUTO}. */
    @NonNull
    public static OrientationPreference fromStorageValue(String value) {
        for (OrientationPreference choice : values()) {
            if (choice.storageValue.equals(value)) return choice;
        }
        return AUTO;
    }

    @NonNull
    public static OrientationPreference load(@NonNull Context context) {
        return fromStorageValue(preferences(context).getString(KEY, AUTO.storageValue));
    }

    public void save(@NonNull Context context) {
        preferences(context).edit().putString(KEY, storageValue).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES,
            Context.MODE_PRIVATE);
    }
}
