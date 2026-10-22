package com.termux.ui.runtime;

import static org.junit.Assert.assertEquals;

import com.termux.ui.data.FileCustomRuntimeComponentRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.domain.GameContainer;
import com.termux.ui.domain.GameRuntimeBackendType;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.domain.RuntimeProfile;
import com.termux.ui.domain.RuntimeProfilePreset;
import com.termux.ui.domain.RuntimeProfilePresets;
import com.termux.ui.domain.RuntimeTranslator;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Collections;

public final class GameContainerProfileResolverTest {

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

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
        RuntimeProfile gameProfile = new RuntimeProfile("game-2", "rootfs_wine_10.0",
            "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", "1280x720", "INTERMEDIATE",
            Collections.emptyMap(), "", LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", "container-stale");
        GameContainer staleContainer = new GameContainer("container-stale", "Independent container",
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", RuntimeTranslator.HANGOVER,
            "rootfs_hangover_11.16", "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap());

        RuntimeProfile resolved = new GameContainerProfileResolver()
            .resolve(gameProfile, staleContainer);

        assertEquals("box64", resolved.getEnvironment().get("GAMES_RUNTIME_TRANSLATOR"));
    }

    @Test
    public void resolvesGlibcOriginCustomWineWithNullTranslatorToBox64() throws Exception {
        // Regression test: a custom-wine-* component installed via the GLIBC_TERMUX_BOX flow is
        // registered with translator == null by design (see CustomRuntimeComponent's doc comment
        // -- that backend has exactly one translation path). resolveTranslator() used to chain
        // Optional.map(CustomRuntimeComponent::getTranslator).orElseThrow(...), and
        // Optional.map() treats a null-returning mapper as "absent", so a correctly-registered
        // GLIBC-origin component was misreported as unregistered (custom_wine_not_registered).
        GameStoragePaths paths = new GameStoragePaths(temporaryFolder.newFolder("files"));
        new FileCustomRuntimeComponentRepository(paths.getCustomRuntimeComponentsDirectory())
            .save(new CustomRuntimeComponent("custom-wine-protonglibc11",
                CustomRuntimeComponent.Kind.WINE, null, "ProtonGlibc11", 1700000000L,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".substring(0, 64)));
        RuntimeProfile gameProfile = new RuntimeProfile("game-3", "custom-wine-protonglibc11",
            "turnip", "dxvk", "alsa", "1280x720", "INTERMEDIATE", Collections.emptyMap(), "",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.GLIBC_TERMUX_BOX, "", "default");
        GameContainer container = new GameContainer("default", "Global GLIBC",
            GameRuntimeBackendType.GLIBC_TERMUX_BOX, "", RuntimeTranslator.BOX64,
            "glibc_wine_9.3-vanilla", "turnip", "dxvk", "alsa", "1280x720", "INTERMEDIATE",
            Collections.emptyMap());

        RuntimeProfile resolved = new GameContainerProfileResolver()
            .resolve(gameProfile, container, paths);

        assertEquals("box64", resolved.getEnvironment().get("GAMES_RUNTIME_TRANSLATOR"));
        assertEquals("/opt/custom-wine/custom-wine-protonglibc11/bin/wine",
            resolved.getEnvironment().get("GAMES_CUSTOM_WINE_PATH"));
    }
}
