package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;

import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.domain.RuntimeTranslator;

import org.junit.Test;

import java.util.Collections;

public final class GameContainerProfileResolverTest {

    @Test
    public void keepsGameLaunchSelectionsAndAppliesContainerSubstrate() {
        RuntimeProfile gameProfile = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);
        GameContainer container = new GameContainer("default", "Global GLIBC",
            GameRuntimeBackendType.GLIBC_TERMUX_BOX, "", RuntimeTranslator.BOX64,
            "container-wine", "container-graphics", "container-dx", "container-audio",
            "800x600", "STABILITY", Collections.singletonMap("CONTAINER_VALUE", "1"));

        RuntimeProfile resolved = new GameContainerProfileResolver().resolve(gameProfile, container);

        assertEquals(gameProfile.getWinePackage(), resolved.getWinePackage());
        assertEquals(gameProfile.getGraphicsDriver(), resolved.getGraphicsDriver());
        assertEquals(gameProfile.getDxWrapper(), resolved.getDxWrapper());
        assertEquals(gameProfile.getResolution(), resolved.getResolution());
        assertEquals(GameRuntimeBackendType.GLIBC_TERMUX_BOX, resolved.getRuntimeBackendType());
        assertEquals("box64", resolved.getEnvironment().get("GAMES_RUNTIME_TRANSLATOR"));
        assertEquals("1", resolved.getEnvironment().get("CONTAINER_VALUE"));
    }
}
