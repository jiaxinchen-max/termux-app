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

    @Test public void nativeGraphicsVersionsAreIndividuallySelectable() throws Exception {
        ComponentIndex index = index();
        ComponentDescriptor dxvk = index.find("native-dxvk-gplasync-2.3").get();
        assertTrue(RuntimeComponentVersions.accepts(dxvk, CustomRuntimeComponent.Kind.DXVK, nativeBackend, null));
        assertEquals(dxvk.getId(), RuntimeComponentVersions.catalogSelectionId(dxvk, CustomRuntimeComponent.Kind.DXVK, nativeBackend));
        ComponentDescriptor wine = index.find("glibc_wine_9.3-vanilla").get();
        assertEquals(wine.getId(), RuntimeComponentVersions.catalogSelectionId(wine, CustomRuntimeComponent.Kind.WINE, nativeBackend));
    }

    @Test public void catalogFiltersInternalScriptsAndIncompatibleWine() throws Exception {
        ComponentIndex index = index();
        for (CustomRuntimeComponent.Kind kind : CustomRuntimeComponent.Kind.values()) {
            assertFalse(RuntimeComponentVersions.accepts(index.find("glibc_scripts_29").get(), kind, nativeBackend, null));
        }
        assertFalse(index.find(RuntimeComponentVersions.PREINSTALLED_HANGOVER).isPresent());
        ComponentDescriptor wine = index.find("rootfs_wine_10.0").get();
        assertTrue(RuntimeComponentVersions.accepts(wine, CustomRuntimeComponent.Kind.WINE, prootBackend, RuntimeTranslator.BOX64));
        assertFalse(RuntimeComponentVersions.accepts(wine, CustomRuntimeComponent.Kind.WINE, prootBackend, RuntimeTranslator.HANGOVER));
        for (String id : new String[] {"glibc_scripts_29", "glibc_fonts_1", "glibc_prefix-apps_3",
                "rootfs_debian13_hangover_pdb2_minimal"}) {
            for (CustomRuntimeComponent.Kind kind : CustomRuntimeComponent.Kind.values()) {
                assertFalse(RuntimeComponentVersions.accepts(index.find(id).get(), kind, nativeBackend, null));
                assertFalse(RuntimeComponentVersions.accepts(index.find(id).get(), kind, prootBackend, null));
            }
        }
        assertFalse(RuntimeComponentVersions.supports(CustomRuntimeComponent.Kind.TURNIP, prootBackend));
        assertFalse(RuntimeComponentVersions.supports(CustomRuntimeComponent.Kind.VKD3D, prootBackend));
    }

    @Test public void hangoverIsContainerProvidedAndCannotBeManuallyInstalled() {
        assertTrue(RuntimeComponentVersions.isPreinstalled("rootfs_hangover_11.16", prootBackend));
        assertFalse(RuntimeComponentVersions.isPreinstalled("rootfs_hangover_11.16", nativeBackend));
        assertFalse(RuntimeComponentVersions.isPreinstalled("rootfs_wine_10.0", prootBackend));
        assertFalse(RuntimeComponentVersions.allowsManualInstall(CustomRuntimeComponent.Kind.WINE,
            prootBackend, RuntimeTranslator.HANGOVER));
        assertTrue(RuntimeComponentVersions.allowsManualInstall(CustomRuntimeComponent.Kind.WINE,
            prootBackend, RuntimeTranslator.BOX64));
        assertTrue(RuntimeComponentVersions.allowsManualInstall(CustomRuntimeComponent.Kind.WINE, nativeBackend, null));
        CustomRuntimeComponent customHangover = new CustomRuntimeComponent("custom-wine-proot-hangover",
            CustomRuntimeComponent.Kind.WINE, RuntimeTranslator.HANGOVER, "custom", 1, repeat('a'), prootBackend);
        assertFalse(RuntimeComponentVersions.accepts(customHangover, CustomRuntimeComponent.Kind.WINE, prootBackend, null));
    }

    @Test public void eachNativeBox64BuildHasAnExactSelectableIdIncludingTheAutomaticDefault() throws Exception {
        ComponentIndex index = index();
        assertFalse(index.find("glibc_box64_0.2.7").isPresent());
        assertFalse(com.termux.ui.wine.runtime.NativeBox64Versions.isBuiltIn("glibc_box64_0.2.7"));
        for (String id : new String[] {"glibc_box64_0.2.8", "glibc_box64_0.3.8", "glibc_box64_0.4.4"}) {
            ComponentDescriptor build = index.find(id).get();
            assertTrue(RuntimeComponentVersions.accepts(build, CustomRuntimeComponent.Kind.BOX64, nativeBackend, null));
            assertFalse(RuntimeComponentVersions.accepts(build, CustomRuntimeComponent.Kind.BOX64, prootBackend, null));
            assertEquals(id, RuntimeComponentVersions.catalogSelectionId(build, CustomRuntimeComponent.Kind.BOX64, nativeBackend));
        }
    }

    @Test public void prebuiltProotBox64IsSelectableWithoutExposingOtherHiddenDependencies() throws Exception {
        for (String id : new String[]{"rootfs_box64_0.2.8", "rootfs_box64_0.3.8", "rootfs_box64_0.4.5"}) {
        ComponentDescriptor build = index().find(id).get();
        assertTrue("Keep the general component catalog clean", build.isHidden());
        assertTrue(RuntimeComponentVersions.accepts(build, CustomRuntimeComponent.Kind.BOX64, prootBackend, null));
        assertTrue(RuntimeComponentVersions.accepts(build, CustomRuntimeComponent.Kind.BOX64, prootBackend, RuntimeTranslator.BOX64));
        assertFalse(RuntimeComponentVersions.accepts(build, CustomRuntimeComponent.Kind.BOX64, prootBackend, RuntimeTranslator.HANGOVER));
        assertFalse(RuntimeComponentVersions.accepts(build, CustomRuntimeComponent.Kind.BOX64, nativeBackend, null));
        assertFalse(RuntimeComponentVersions.accepts(build, CustomRuntimeComponent.Kind.WINE, prootBackend, null));
        assertEquals(id, RuntimeComponentVersions.catalogSelectionId(build,
            CustomRuntimeComponent.Kind.BOX64, prootBackend));
        }
    }

    @Test public void box64DefaultsMatchEachRuntimeAndExplicitSelectionsRemainExact() {
        assertEquals("rootfs_box64_0.4.5", RuntimeComponentVersions.selectedBox64Id(prootBackend, ""));
        assertEquals("rootfs_box64_0.4.5", RuntimeComponentVersions.selectedBox64Id(prootBackend, null));
        assertEquals("glibc_box64_0.2.8", RuntimeComponentVersions.selectedBox64Id(nativeBackend, ""));
        assertEquals("glibc_box64_0.4.4", RuntimeComponentVersions.selectedBox64Id(nativeBackend, "glibc_box64_0.4.4"));
        assertEquals("custom-box64-test", RuntimeComponentVersions.selectedBox64Id(prootBackend, "custom-box64-test"));
    }

    private ComponentIndex index() throws Exception {
        try (FileInputStream input = new FileInputStream("src/main/assets/termux-app-packages/index-v1.json")) {
            return new ComponentIndexParser().parse(input);
        }
    }
    private String repeat(char c) { return new String(new char[64]).replace('\0', c); }
}
