package com.termux.localgames.runtime;

import static org.junit.Assert.assertEquals;

import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.LaunchExecutionMode;
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

    @Test
    public void derivesTranslatorFromCurrentWinePackageNotStaleContainerValue() {
        // Regression test: the container's own translator is set once, at container-creation
        // time (see GameContainerFactory.fromProfile()), from whatever winePackage the profile
        // had then. If the profile's "Wine package" picker changes afterwards without the
        // container being recreated, GAMES_RUNTIME_TRANSLATOR must still follow the profile's
        // *current* winePackage -- not the container's now-stale translator -- or
        // rootfs_prefix_warmup.sh's resolve_rootfs_translator() rejects the launch with
        // runtime_translator_package_mismatch (WINE_PACKAGE no longer matching RUNTIME_TRANSLATOR).
        RuntimeProfile gameProfile = new RuntimeProfile("game-2", "box64-wine-latest",
            "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", "1280x720", "INTERMEDIATE",
            Collections.emptyMap(), "", LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", "container-stale");
        GameContainer staleContainer = new GameContainer("container-stale", "Independent container",
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", RuntimeTranslator.HANGOVER,
            "hangover-latest", "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap());

        RuntimeProfile resolved = new GameContainerProfileResolver()
            .resolve(gameProfile, staleContainer);

        assertEquals("box64", resolved.getEnvironment().get("GAMES_RUNTIME_TRANSLATOR"));
    }
}
