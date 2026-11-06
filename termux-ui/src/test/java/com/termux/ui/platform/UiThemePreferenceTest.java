package com.termux.ui.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import androidx.appcompat.app.AppCompatDelegate;

import org.junit.Test;

public final class UiThemePreferenceTest {
    @Test public void choicesHaveExplicitNightModes() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, UiThemePreference.AUTO.getNightMode());
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, UiThemePreference.LIGHT.getNightMode());
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, UiThemePreference.DARK.getNightMode());
    }

    @Test public void savedChoicesRoundTrip() {
        for (UiThemePreference choice : UiThemePreference.values()) {
            assertEquals(choice, UiThemePreference.fromStorageValue(choice.getStorageValue()));
        }
    }

    @Test public void invalidChoiceIsRejectedRatherThanChangingTheMode() {
        assertThrows(IllegalArgumentException.class, () -> UiThemePreference.fromStorageValue("system"));
        assertThrows(IllegalArgumentException.class, () -> UiThemePreference.fromStorageValue(null));
    }
}
