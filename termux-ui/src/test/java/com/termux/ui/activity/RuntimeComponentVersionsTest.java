package com.termux.ui.activity;

import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeTranslator;
import org.junit.Test;
import java.io.FileInputStream;
import static org.junit.Assert.*;

public class RuntimeComponentVersionsTest {
    private final GameRuntimeBackendType nativeBackend = GameRuntimeBackendType.GLIBC_TERMUX_BOX;
    private final GameRuntimeBackendType prootBackend = GameRuntimeBackendType.ROOTFS_PROOT;

    @Test public void installedComponentsNeverCrossRuntimeScopes() {
        CustomRuntimeComponent component = new CustomRuntimeComponent("custom-dxvk-native-one",
            CustomRuntimeComponent.Kind.DXVK, null, "DXVK one", 1, repeat('a'), nativeBackend);
        assertTrue(RuntimeComponentVersions.accepts(component, CustomRuntimeComponent.Kind.DXVK, nativeBackend, null));
        assertFalse(RuntimeComponentVersions.accepts(component, CustomRuntimeComponent.Kind.DXVK, prootBackend, null));
        assertFalse(RuntimeComponentVersions.accepts(component, CustomRuntimeComponent.Kind.VKD3D, nativeBackend, null));
    }

    @Test public void installedWineHonorsExistingPrefixTranslator() {
        CustomRuntimeComponent component = new CustomRuntimeComponent("custom-wine-proot-one",
            CustomRuntimeComponent.Kind.WINE, RuntimeTranslator.BOX64, "Wine one", 1, repeat('b'), prootBackend);
        assertTrue(RuntimeComponentVersions.accepts(component, CustomRuntimeComponent.Kind.WINE, prootBackend, RuntimeTranslator.BOX64));
        assertFalse(RuntimeComponentVersions.accepts(component, CustomRuntimeComponent.Kind.WINE, prootBackend, RuntimeTranslator.HANGOVER));
    }

    @Test public void nativeBundlesCannotBeSavedAsVersionSelections() throws Exception {
        ComponentIndex index = index();
        ComponentDescriptor dxvk = index.find("dxvk").get();
        assertTrue(RuntimeComponentVersions.accepts(dxvk, CustomRuntimeComponent.Kind.DXVK, nativeBackend, null));
        assertNull(RuntimeComponentVersions.catalogSelectionId(dxvk, CustomRuntimeComponent.Kind.DXVK, nativeBackend));
        ComponentDescriptor wine = index.find("glibc_wine_9.3-vanilla").get();
        assertEquals(wine.getId(), RuntimeComponentVersions.catalogSelectionId(wine, CustomRuntimeComponent.Kind.WINE, nativeBackend));
    }

    @Test public void catalogFiltersInternalScriptsAndIncompatibleWine() throws Exception {
        ComponentIndex index = index();
        for (CustomRuntimeComponent.Kind kind : CustomRuntimeComponent.Kind.values()) {
            assertFalse(RuntimeComponentVersions.accepts(index.find("glibc_scripts_29").get(), kind, nativeBackend, null));
        }
        ComponentDescriptor hangover = index.find("rootfs_hangover_11.16").get();
        assertFalse(RuntimeComponentVersions.accepts(hangover, CustomRuntimeComponent.Kind.WINE, prootBackend, RuntimeTranslator.BOX64));
        assertTrue(RuntimeComponentVersions.accepts(hangover, CustomRuntimeComponent.Kind.WINE, prootBackend, RuntimeTranslator.HANGOVER));
        assertFalse(RuntimeComponentVersions.supports(CustomRuntimeComponent.Kind.TURNIP, prootBackend));
        assertFalse(RuntimeComponentVersions.supports(CustomRuntimeComponent.Kind.VKD3D, prootBackend));
    }

    private ComponentIndex index() throws Exception {
        try (FileInputStream input = new FileInputStream("src/main/assets/termux-app-packages/index-v1.json")) {
            return new ComponentIndexParser().parse(input);
        }
    }
    private String repeat(char c) { return new String(new char[64]).replace('\0', c); }
}
