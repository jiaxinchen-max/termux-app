package com.termux.ui.wine.runtime;

import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class HangoverEmulationModeTest {
    @Test public void defaultAndExplicitModesKeepHangoverDependenciesUnchanged() {
        RuntimeProfile defaults = profile(GameRuntimeBackendType.ROOTFS_PROOT, "rootfs_hangover_11.16", "");
        assertEquals(HangoverEmulationMode.WOWBOX64, HangoverEmulationMode.selected(defaults));
        for (String mode : new String[] {"wowbox64", "fex"}) {
            RuntimeProfile selected = profile(GameRuntimeBackendType.ROOTFS_PROOT, "rootfs_hangover_11.16", mode);
            assertEquals(new RootfsProotBackend().requiredComponentIds(defaults),
                new RootfsProotBackend().requiredComponentIds(selected));
            assertEquals("local-games/start_rootfs_game.sh", new RootfsProotBackend().getLauncherAssetPath());
        }
    }

    @Test public void invalidModeCannotPassLaunchAdmission() {
        try {
            new RootfsProotBackend().requiredComponentIds(profile(GameRuntimeBackendType.ROOTFS_PROOT,
                "rootfs_hangover_11.16", "invented"));
            fail("invalid emulator accepted");
        } catch (IllegalArgumentException expected) {
            assertEquals("hangover_emulator_invalid", expected.getMessage());
        }
    }

    @Test public void otherWineFamiliesDoNotUseHangoverMode() {
        for (String wine : new String[] {"rootfs_wine_10.0", "rootfs_proton_11.0"}) {
            RuntimeProfile selected = profile(GameRuntimeBackendType.ROOTFS_PROOT, wine, "fex");
            assertFalse(HangoverEmulationMode.appliesTo(selected));
            assertEquals(new RootfsProotBackend().requiredComponentIds(
                profile(GameRuntimeBackendType.ROOTFS_PROOT, wine, "")),
                new RootfsProotBackend().requiredComponentIds(selected));
        }
        assertFalse(HangoverEmulationMode.appliesTo(profile(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
            "glibc_wine_9.3-vanilla", "fex")));
    }

    private static RuntimeProfile profile(GameRuntimeBackendType backend, String wine, String mode) {
        return new RuntimeProfile("mode-test", wine, "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio",
            "1280x720", "INTERMEDIATE", mode.isEmpty() ? Collections.emptyMap()
                : Collections.singletonMap(HangoverEmulationMode.ENVIRONMENT_KEY, mode),
            "xinput", LaunchExecutionMode.APP_SHELL, Collections.emptyMap(), backend,
            backend == GameRuntimeBackendType.ROOTFS_PROOT ? RootfsSetupRecipe.DEFAULT_PACKAGE : "");
    }
}
