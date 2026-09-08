package com.termux.app.localgames;

import androidx.annotation.NonNull;

import com.termux.localgames.api.AppExperienceMode;

/** Pure routing policy used before TermuxActivity initializes UI capabilities. */
public final class TermuxAppExperienceRouter {
    private TermuxAppExperienceRouter() { }

    public static boolean shouldOpenGames(@NonNull AppExperienceMode mode,
                                          boolean explicitSessionOnly) {
        return mode == AppExperienceMode.GAMES && !explicitSessionOnly;
    }

    public static boolean shouldIntegrateX11(@NonNull AppExperienceMode mode) {
        return mode == AppExperienceMode.TERMINAL;
    }
}
