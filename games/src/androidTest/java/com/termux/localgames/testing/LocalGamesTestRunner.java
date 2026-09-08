package com.termux.localgames.testing;

import android.app.Application;
import android.content.Context;

import androidx.test.runner.AndroidJUnitRunner;

public final class LocalGamesTestRunner extends AndroidJUnitRunner {

    @Override
    public Application newApplication(ClassLoader classLoader, String className,
                                      Context context) throws InstantiationException,
        IllegalAccessException, ClassNotFoundException {
        return super.newApplication(classLoader,
            LocalGamesTestApplication.class.getName(), context);
    }
}
