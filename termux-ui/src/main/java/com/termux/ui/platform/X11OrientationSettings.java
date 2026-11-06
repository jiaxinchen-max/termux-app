package com.termux.ui.platform;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Default orientation for desktop sessions. Games and the app UI store their own choices. */
public final class X11OrientationSettings {
    private static final String PREFERENCES = "x11_orientation_settings";
    private static final String KEY = "orientation";

    private X11OrientationSettings() {}

    @NonNull
    public static OrientationPreference load(@NonNull Context context) {
        return OrientationPreference.fromStorageValue(preferences(context)
            .getString(KEY, OrientationPreference.AUTO.getStorageValue()));
    }

    public static void save(@NonNull Context context, @NonNull OrientationPreference value) {
        preferences(context).edit().putString(KEY, value.getStorageValue()).apply();
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
