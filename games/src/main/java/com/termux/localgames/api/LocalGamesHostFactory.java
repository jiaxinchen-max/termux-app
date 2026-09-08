package com.termux.localgames.api;

import android.content.Context;

import androidx.annotation.NonNull;

/** Creates process-local host adapters without retaining an Activity. */
public interface LocalGamesHostFactory {

    @NonNull
    LocalGamesHost create(@NonNull Context applicationContext);
}
