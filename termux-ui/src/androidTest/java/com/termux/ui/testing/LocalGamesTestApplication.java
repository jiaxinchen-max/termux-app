package com.termux.ui.testing;

import android.app.Application;

import com.termux.ui.api.LocalGames;
import com.termux.ui.api.LocalGamesHost;

/** Installs the module host contract before test Activities or Services are created. */
public final class LocalGamesTestApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        LocalGames.install(applicationContext -> new LocalGamesHost() {
            @Override
            public boolean isRuntimeAvailable() {
                return true;
            }
        });
    }
}
