package com.termux.ui.wine.runtime;

import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.components.index.ComponentType;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import org.junit.Test;
import static org.junit.Assert.*;

public class NativePrebuiltPrefixesTest {
    @Test public void eachWineHasItsOwnHiddenNativeResource() throws Exception {
        ComponentIndex index = new ComponentIndexParser().parse(getClass().getClassLoader()
            .getResourceAsStream("termux-app-packages/index-v1.json"));
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (String wine : NativePrebuiltPrefixes.WINES) {
            String id = NativePrebuiltPrefixes.componentId(wine);
            assertTrue(ids.add(id));
            assertTrue(NativePrebuiltPrefixes.isComponentId(id));
            ComponentDescriptor descriptor = index.find(id).get();
            assertEquals(ComponentType.RUNTIME_SUPPORT, descriptor.getType());
            assertTrue(descriptor.isHidden());
            assertFalse(descriptor.isSelectable());
            assertTrue(descriptor.supportsBackend(GameRuntimeBackendType.GLIBC_TERMUX_BOX));
            assertFalse(descriptor.supportsBackend(GameRuntimeBackendType.ROOTFS_PROOT));
            assertTrue(descriptor.getSize() > 0);
            assertTrue(descriptor.getSha256().matches("[0-9a-f]{64}"));
        }
        assertEquals(5, ids.size());
    }

    @Test public void customAndProotPackagesCannotBorrowANativeAsset() {
        for (String wine : new String[]{"custom-wine-1", "rootfs_wine_10.0", "glibc_wine_11.0", ""}) {
            try { NativePrebuiltPrefixes.componentId(wine); fail(wine); }
            catch (IllegalArgumentException expected) { }
        }
        assertFalse(NativePrebuiltPrefixes.isComponentId("rootfs_prefix_wine10_wow64"));
        assertFalse(NativePrebuiltPrefixes.isComponentId("native_prefix_unknown"));
    }

    @Test public void changingWineKeepsEachPrefixAndProotPathIndependent() throws Exception {
        com.termux.ui.data.GameStoragePaths paths = new com.termux.ui.data.GameStoragePaths(new java.io.File("/tmp/native-prefix-paths"));
        java.io.File wine = paths.getNativePrefixDirectory("default", "glibc_wine_10.0");
        java.io.File proton = paths.getNativePrefixDirectory("default", "glibc_proton_11.0");
        assertNotEquals(wine, proton);
        assertEquals(wine, paths.getNativePrefixDirectory("default", "glibc_wine_10.0"));
        assertNotEquals(wine, paths.getNativePrefixDirectory("other", "glibc_wine_10.0"));
        assertEquals(wine, paths.getContainerPrefixDirectory("default", GameRuntimeBackendType.GLIBC_TERMUX_BOX, "glibc_wine_10.0"));
        assertEquals(paths.getContainerPrefixDirectory("default", GameRuntimeBackendType.ROOTFS_PROOT),
            paths.getContainerPrefixDirectory("default", GameRuntimeBackendType.ROOTFS_PROOT, "rootfs_wine_10.0"));
        for (String invalid : new String[]{"..", "../outside", "rootfs_wine_10.0"}) {
            try { paths.getNativePrefixDirectory("default", invalid); fail(invalid); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
