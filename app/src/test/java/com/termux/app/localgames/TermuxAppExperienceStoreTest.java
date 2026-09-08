package com.termux.app.localgames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.termux.localgames.api.AppExperienceMode;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class TermuxAppExperienceStoreTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("termux_app_experience", Context.MODE_PRIVATE)
            .edit().clear().commit();
    }

    @Test public void defaultsToTerminalAndPersistsGames() {
        TermuxAppExperienceStore store = new TermuxAppExperienceStore(context);
        assertEquals(AppExperienceMode.TERMINAL, store.get());
        assertTrue(store.set(AppExperienceMode.GAMES));
        assertEquals(AppExperienceMode.GAMES, new TermuxAppExperienceStore(context).get());
    }

    @Test public void invalidStoredValueFallsBackToTerminal() {
        context.getSharedPreferences("termux_app_experience", Context.MODE_PRIVATE)
            .edit().putString("mode", "BROKEN").commit();
        assertEquals(AppExperienceMode.TERMINAL,
            new TermuxAppExperienceStore(context).get());
    }
}
