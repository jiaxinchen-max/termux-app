package com.termux.ui.wine.runtime;

import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.domain.*;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.util.Collections;
import static org.junit.Assert.*;

public class ProotRendererChangeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private RuntimeProfile profile(String wine, String graphics, String dx) {
        return new RuntimeProfile("game", wine, graphics, dx, "pulseaudio", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput", LaunchExecutionMode.APP_SHELL,
            Collections.emptyMap(), GameRuntimeBackendType.ROOTFS_PROOT, "ubuntu-prootfs", "container");
    }
    private RuntimeProfile software() {
        return profile("rootfs_wine_10.0", "rootfs-llvmpipe", "rootfs-wined3d");
    }
    private RuntimeProfile virgl() {
        return profile("rootfs_wine_10.0", "rootfs-virgl-mesa", "rootfs-wined3d");
    }
    @Test public void rendererChangesInEitherDirectionReuseThePreparedIdentity() {
        assertTrue(ProotRendererChange.isRendererOnly(software(), virgl()));
        assertTrue(ProotRendererChange.isRendererOnly(virgl(), software()));
    }
    @Test public void importsAndUnchangedSavesKeepTheExistingPreparationFlow() {
        assertFalse(ProotRendererChange.isRendererOnly(null, software()));
        assertFalse(ProotRendererChange.isRendererOnly(software(), software()));
    }
    @Test public void differentContainersAndGamesCannotReuseTheFastPath() {
        assertFalse(ProotRendererChange.isRendererOnly(software(), virgl().withContainerId("other")));
        assertFalse(ProotRendererChange.isRendererOnly(software().withContainerId("default"),
            virgl().withContainerId("default")));
    }
    @Test public void engineAndDxChangesStillRequirePreparation() {
        assertFalse(ProotRendererChange.isRendererOnly(software(),
            profile("rootfs_proton_11.0", "rootfs-turnip", "rootfs-wined3d")));
        assertFalse(ProotRendererChange.isRendererOnly(software(),
            profile("rootfs_wine_10.0", "rootfs-turnip", "rootfs_dxvk_2.7")));
    }
    @Test public void combinedDisplayChangeDoesNotTakeTheRendererOnlyPath() {
        assertFalse(ProotRendererChange.isRendererOnly(software(), virgl().withResolution("1920x1080")));
    }
    @Test public void nativeProfilesKeepTheirOwnPreparationFlow() {
        assertFalse(ProotRendererChange.isRendererOnly(
            software().withRuntimeBackend(GameRuntimeBackendType.GLIBC_TERMUX_BOX, ""),
            virgl().withRuntimeBackend(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "")));
    }
    @Test public void turnipNeedsTheExactIcdUsedByTheLauncher() throws Exception {
        File root = temporary.newFolder();
        assertFalse(ProotRendererChange.backendFilesReady(root, "rootfs-turnip"));
        File icd = new File(root, "usr/share/vulkan/icd.d/freedreno_icd.json");
        assertTrue(icd.getParentFile().mkdirs());
        assertTrue(icd.createNewFile());
        assertTrue(ProotRendererChange.backendFilesReady(root, "rootfs-turnip"));
        assertFalse(ProotRendererChange.backendFilesReady(root, "unknown"));
    }
}
