package com.termux.ui.platform;

import android.content.Context;

/** Optional IDE UI; hiding it never removes projects or installed tools. */
public final class IdePreference {
    private IdePreference() { }

    public static boolean isEnabled(Context context) {
        return context.getSharedPreferences("local_games_settings", Context.MODE_PRIVATE)
            .getBoolean("ide_enabled", false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences("local_games_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("ide_enabled", enabled).apply();
    }
}
