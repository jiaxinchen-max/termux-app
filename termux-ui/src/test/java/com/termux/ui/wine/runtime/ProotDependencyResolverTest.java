package com.termux.ui.wine.runtime;

import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProotDependencyResolverTest {
    @Test public void defaultRecipeOnlyDownloadsPreinstalledImage() {
        RootfsSetupRecipe recipe = RootfsSetupRecipe.require(RootfsSetupRecipe.DEFAULT_PACKAGE);
        assertEquals(12, recipe.getVersion());
        assertEquals(Collections.singletonList(RootfsSetupRecipe.IMAGE_COMPONENT), recipe.getBaseComponentIds());
    }
    private Set<String> resolve(String wine, String graphics, String dx) {
        RuntimeProfile p = new RuntimeProfile("game", wine, graphics, dx, "pulseaudio",
            "1280x720", "INTERMEDIATE", Collections.emptyMap(), "xinput",
            LaunchExecutionMode.APP_SHELL, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", "container");
        return new ProotDependencyResolver().requiredComponentIds(p, null);
    }
    @Test public void hangoverWined3dOnlyNeedsBaseSource() {
        assertEquals(Collections.singleton(RootfsSetupRecipe.IMAGE_COMPONENT),
            resolve("rootfs_hangover_11.16", "rootfs-llvmpipe", "rootfs-wined3d"));
    }
    @Test public void protonOnlyRequestsItsTranslatorAndSelectedDxvk() {
        assertEquals(new LinkedHashSet<>(Arrays.asList(RootfsSetupRecipe.IMAGE_COMPONENT,
            "rootfs_proton_11.0", "rootfs_box64_0.4.4", "rootfs_dxvk_3.1")),
            resolve("rootfs_proton_11.0", "rootfs-llvmpipe", "rootfs_dxvk_3.1"));
    }
    @Test public void wineWined3dDoesNotPullProtonOrDxvk() {
        assertEquals(new LinkedHashSet<>(Arrays.asList(RootfsSetupRecipe.IMAGE_COMPONENT,
            "rootfs_wine_10.0", "rootfs_box64_0.4.4")),
            resolve("rootfs_wine_10.0", "rootfs-llvmpipe", "rootfs-wined3d"));
    }
    @Test(expected = IllegalArgumentException.class) public void invalidCombinationStillFails() {
        resolve("rootfs_wine_10.0", "rootfs-virgl-mesa", "rootfs_dxvk_3.1");
    }
}
