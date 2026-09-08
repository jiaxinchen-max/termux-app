package com.termux.app.localgames;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.termux.localgames.api.LocalGames;

/** Host-app navigation adapter for the module-owned Local Games activity. */
public final class TermuxLocalGamesEntry {

    private TermuxLocalGamesEntry() {}

    @NonNull
    public static Intent createIntent(@NonNull Context context) {
        return LocalGames.createLaunchIntent(context);
    }
}
