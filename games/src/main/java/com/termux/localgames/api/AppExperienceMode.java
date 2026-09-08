package com.termux.localgames.api;

/** Process-frozen top-level interaction mode; independent from launch runner selection. */
public enum AppExperienceMode {
    TERMINAL,
    GAMES;

    public static AppExperienceMode fromPersistedValue(String value) {
        if (value == null) return TERMINAL;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException error) {
            return TERMINAL;
        }
    }
}
