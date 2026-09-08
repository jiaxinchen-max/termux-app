package com.termux.app.localgames;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import com.termux.localgames.api.AppExperienceMode;

/** Main-app owner for the process-frozen Terminal/Games mode. */
public final class TermuxAppExperienceStore {
    private static final String PREFERENCES = "termux_app_experience";
    private static final String KEY_MODE = "mode";

    private final SharedPreferences preferences;

    public TermuxAppExperienceStore(@NonNull Context context) {
        preferences = context.getApplicationContext()
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    @NonNull
    public AppExperienceMode get() {
        return AppExperienceMode.fromPersistedValue(
            preferences.getString(KEY_MODE, AppExperienceMode.TERMINAL.name()));
    }

    public boolean set(@NonNull AppExperienceMode mode) {
        return preferences.edit().putString(KEY_MODE, mode.name()).commit();
    }
}
