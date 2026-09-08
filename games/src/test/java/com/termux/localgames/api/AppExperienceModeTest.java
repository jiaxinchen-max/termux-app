package com.termux.localgames.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AppExperienceModeTest {
    @Test public void missingAndInvalidValuesPreserveTerminalCompatibility() {
        assertEquals(AppExperienceMode.TERMINAL, AppExperienceMode.fromPersistedValue(null));
        assertEquals(AppExperienceMode.TERMINAL, AppExperienceMode.fromPersistedValue("INVALID"));
    }

    @Test public void persistedGamesValueIsRestored() {
        assertEquals(AppExperienceMode.GAMES, AppExperienceMode.fromPersistedValue("GAMES"));
    }
}
