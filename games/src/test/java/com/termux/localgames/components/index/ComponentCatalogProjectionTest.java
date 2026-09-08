package com.termux.localgames.components.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.GameRuntimeBackendType;

import org.junit.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Guards the GameHub slot projection of the bundled catalog. */
public class ComponentCatalogProjectionTest {

    private static ComponentIndex bundledIndex() throws Exception {
        InputStream input = ComponentCatalogProjectionTest.class.getClassLoader()
            .getResourceAsStream("termux-box-packages/index-v1.json");
        assertTrue("bundled index must be available to tests", input != null);
        try (InputStream closeable = input) {
            return new ComponentIndexParser().parse(closeable);
        }
    }

    @Test
    public void sectionOrderFollowsFixedGameHubSlots() {
        assertEquals(List.of(ComponentType.IMAGE_FS, ComponentType.CONTAINER,
                ComponentType.GPU_DRIVER, ComponentType.DX_WRAPPER,
                ComponentType.TRANSLATOR, ComponentType.GENERAL_COMPONENT,
                ComponentType.RUNTIME_SUPPORT),
            List.of(ComponentType.values()));
    }

    @Test
    public void bundledResourcesMapToTheirGameHubSlot() throws Exception {
        ComponentIndex index = bundledIndex();
        assertEquals(ComponentType.CONTAINER,
            index.find("wine-9.3-vanilla-wow64").get().getType());
        assertEquals(ComponentType.GPU_DRIVER, index.find("turnip").get().getType());
        assertEquals(ComponentType.GPU_DRIVER, index.find("virgl-mesa").get().getType());
        assertEquals(ComponentType.DX_WRAPPER, index.find("dxvk").get().getType());
        assertEquals(ComponentType.DX_WRAPPER, index.find("wined3d").get().getType());
        assertEquals(ComponentType.TRANSLATOR, index.find("box64-binaries").get().getType());
        assertEquals(ComponentType.IMAGE_FS,
            index.find("hangover-11.9-debian13-source").get().getType());
        assertEquals(ComponentType.RUNTIME_SUPPORT, index.find("scripts").get().getType());
        assertEquals(ComponentType.RUNTIME_SUPPORT, index.find("glibc-prefix").get().getType());
    }

    @Test
    public void everyComponentCarriesReadableIdentityInsteadOfBareId() throws Exception {
        for (ComponentDescriptor descriptor : bundledIndex().getComponents()) {
            assertFalse("displayName must not be the bare technical id: " + descriptor.getId(),
                descriptor.getDisplayName().equals(descriptor.getId()));
            assertFalse(descriptor.getVersionName().trim().isEmpty());
            assertFalse(descriptor.getSummary().trim().isEmpty());
            assertFalse(descriptor.getFramework().trim().isEmpty());
        }
    }

    @Test
    public void internalSupportPackagesAreNeverUserSelectable() throws Exception {
        for (ComponentDescriptor descriptor : bundledIndex().getComponents()) {
            if (descriptor.getType() == ComponentType.RUNTIME_SUPPORT ||
                descriptor.getType() == ComponentType.GENERAL_COMPONENT ||
                descriptor.getType() == ComponentType.IMAGE_FS) {
                assertFalse("internal package must not appear in profile selectors: " +
                    descriptor.getId(), descriptor.isSelectable());
            }
        }
    }

    @Test
    public void selectorsOnlyOfferComponentsForTheRequestedBackend() throws Exception {
        ComponentIndex index = bundledIndex();
        List<String> glibcContainers = new ArrayList<>();
        for (ComponentDescriptor descriptor : index.selectable(ComponentType.CONTAINER,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX)) {
            glibcContainers.add(descriptor.getId());
            assertTrue(descriptor.supportsBackend(GameRuntimeBackendType.GLIBC_TERMUX_BOX));
        }
        assertTrue(glibcContainers.contains("wine-9.3-vanilla-wow64"));
        assertEquals("recommended Wine must be offered first",
            "wine-9.3-vanilla-wow64", glibcContainers.get(0));
        assertTrue("RootFS ImageFS source is not a selectable container",
            index.selectable(ComponentType.CONTAINER,
                GameRuntimeBackendType.ROOTFS_PROOT).isEmpty());
    }

    @Test
    public void unavailableGameHubSlotsHaveNoDownloadableEntry() throws Exception {
        for (ComponentDescriptor descriptor : bundledIndex().getComponents()) {
            String id = descriptor.getId().toLowerCase();
            assertFalse("VKD3D has no verified delivery yet", id.contains("vkd3d"));
            assertFalse("FEX has no verified delivery yet", id.contains("fex"));
            assertFalse("Steam client has no verified delivery yet", id.contains("steam"));
        }
    }

    @Test
    public void prefixAppsStaysOneArchiveInsteadOfFakeMembers() throws Exception {
        ComponentIndex index = bundledIndex();
        assertEquals(ComponentType.GENERAL_COMPONENT,
            index.find("prefix-apps").get().getType());
        assertFalse(index.find("mono").isPresent());
        assertFalse(index.find("gecko").isPresent());
        assertFalse(index.find("physx").isPresent());
        assertFalse(index.find("fonts").isPresent());
    }
}
