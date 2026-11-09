package com.termux.ui.wine.runtime;

import com.termux.ui.api.RuntimeComponentVariant;
import com.termux.ui.components.index.*;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.*;
import java.io.InputStream;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;

public class WineDisplayNamesTest {
    private ComponentIndex index;
    @Before public void loadIndex() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("termux-app-packages/index-v1.json")) {
            index = new ComponentIndexParser().parse(input);
        }
    }
    private RuntimeProfile profile(String wine, GameRuntimeBackendType backend) {
        RuntimeProfile p = RuntimeProfilePresets.create("game", RuntimeProfilePreset.RECOMMENDED);
        return new RuntimeProfile(p.getId(), wine, p.getGraphicsDriver(), p.getDxWrapper(), p.getAudioDriver(),
            p.getResolution(), p.getBox64Preset(), p.getEnvironment(), p.getInputProfileId(),
            p.getLaunchExecutionMode(), p.getComponentVersions(), backend,
            backend == GameRuntimeBackendType.ROOTFS_PROOT ? GameContainer.ROOTFS_RUNTIME_PACKAGE : "",
            backend == GameRuntimeBackendType.ROOTFS_PROOT ? "container-game" : "default");
    }
    @Test public void catalogNamesTrackExactSavedWineAndVersionOnBothBackends() {
        for (String wine : Arrays.asList("glibc_wine_9.3-vanilla", "glibc_proton_11.0", "rootfs_wine_10.0", "rootfs_proton_11.0")) {
            GameRuntimeBackendType backend = wine.startsWith("rootfs_") ? GameRuntimeBackendType.ROOTFS_PROOT : GameRuntimeBackendType.GLIBC_TERMUX_BOX;
            assertEquals(index.find(wine).get().getDisplayName(), WineDisplayNames.resolve(profile(wine, backend),
                Collections.emptyList(), Collections.emptyList(), index));
        }
    }
    @Test public void localInstalledNamesTakePrecedenceOverCatalog() {
        String wine = "glibc_wine_9.3-vanilla";
        List<RuntimeComponentVariant> variants = Arrays.asList(new RuntimeComponentVariant(wine, "Wine 9.3 locally installed",
            CustomRuntimeComponent.Kind.WINE, GameRuntimeBackendType.GLIBC_TERMUX_BOX));
        assertEquals("Wine 9.3 locally installed", WineDisplayNames.resolve(profile(wine, GameRuntimeBackendType.GLIBC_TERMUX_BOX),
            Collections.emptyList(), variants, index));
    }
    @Test public void customNamesAreScopedByKindAndBackend() {
        String wine = "custom-wine-local";
        CustomRuntimeComponent component = new CustomRuntimeComponent(wine, CustomRuntimeComponent.Kind.WINE,
            null, "Wine 10.0 custom", 1, String.join("", Collections.nCopies(64, "a")), GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        assertEquals("Wine 10.0 custom", WineDisplayNames.resolve(profile(wine, GameRuntimeBackendType.GLIBC_TERMUX_BOX),
            Arrays.asList(component), Collections.emptyList(), index));
        assertEquals(wine, WineDisplayNames.resolve(profile(wine, GameRuntimeBackendType.ROOTFS_PROOT),
            Arrays.asList(component), Collections.emptyList(), index));
    }
    @Test public void missingNameDisplaysActualSavedIdentityWithoutDefaulting() {
        assertEquals("custom-wine-missing", WineDisplayNames.resolve(profile("custom-wine-missing", GameRuntimeBackendType.GLIBC_TERMUX_BOX),
            Collections.emptyList(), Collections.emptyList(), index));
    }
}
