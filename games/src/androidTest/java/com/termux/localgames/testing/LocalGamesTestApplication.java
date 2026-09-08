package com.termux.localgames.testing;

import android.app.Application;

import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LocalGamesHost;

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
