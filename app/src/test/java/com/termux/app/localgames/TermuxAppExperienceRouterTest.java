package com.termux.app.localgames;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.api.AppExperienceMode;

import org.junit.Test;

public class TermuxAppExperienceRouterTest {
    @Test public void terminalModeKeepsIntegratedX11() {
        assertFalse(TermuxAppExperienceRouter.shouldOpenGames(
            AppExperienceMode.TERMINAL, false));
        assertTrue(TermuxAppExperienceRouter.shouldIntegrateX11(
            AppExperienceMode.TERMINAL));
    }

    @Test public void gamesLauncherRoutesToGames() {
        assertTrue(TermuxAppExperienceRouter.shouldOpenGames(
            AppExperienceMode.GAMES, false));
        assertFalse(TermuxAppExperienceRouter.shouldIntegrateX11(
            AppExperienceMode.GAMES));
    }

    @Test public void explicitGamesTerminalDoesNotRouteAndHasNoX11() {
        assertFalse(TermuxAppExperienceRouter.shouldOpenGames(
            AppExperienceMode.GAMES, true));
        assertFalse(TermuxAppExperienceRouter.shouldIntegrateX11(
            AppExperienceMode.GAMES));
    }
}
