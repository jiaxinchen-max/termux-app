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
            index.find("glibc_wine_9.3-vanilla").get().getType());
        assertEquals(ComponentType.GPU_DRIVER, index.find("turnip").get().getType());
        assertEquals(ComponentType.GPU_DRIVER, index.find("virgl-mesa").get().getType());
        assertEquals(ComponentType.DX_WRAPPER, index.find("dxvk").get().getType());
        assertEquals(ComponentType.DX_WRAPPER, index.find("wined3d").get().getType());
        assertEquals(ComponentType.TRANSLATOR, index.find("glibc_box64_0.2.7").get().getType());
        assertEquals(ComponentType.TRANSLATOR,
            index.find("glibc_box64_0.4.4").get().getType());
        assertFalse(index.find("glibc_box64_0.4.4").get().isBase());
        assertEquals("box64_0.2.7", index.find("glibc_box64_0.2.7").get().getDisplayName());
        assertEquals("box64_0.4.4",
            index.find("glibc_box64_0.4.4").get().getDisplayName());
        assertEquals(ComponentType.RUNTIME_SUPPORT,
            index.find("rootfs_hangover_11.16").get().getType());
        assertEquals(ComponentType.RUNTIME_SUPPORT,
            index.find("glibc_scripts_29").get().getType());
        assertFalse(index.find("termux-glibc").isPresent());
    }

    @Test
    public void displayNamesFollowComponentUnderscoreVersion() throws Exception {
        // Ids are <env>_<component>_<version>; the user-facing name drops the env and keeps
        // <component>_<version>. Components whose id predates the scheme (dxvk, turnip, ...) are
        // profile enum values, so they only gain the version suffix.
        for (ComponentDescriptor descriptor : bundledIndex().getComponents()) {
            String id = descriptor.getId();
            String expectedPrefix = id.replaceFirst("^(glibc|rootfs)_", "");
            String name = descriptor.getDisplayName();
            assertTrue(id + " -> " + name, name.matches("[a-z0-9-]+_[0-9][0-9A-Za-z.-]*"));
            if (!expectedPrefix.equals(id)) {
                assertEquals(expectedPrefix, name);
            } else {
                assertTrue(id + " -> " + name, name.startsWith(id + "_"));
            }
        }
    }

    @Test
    public void everyComponentCarriesReadableIdentity() throws Exception {
        for (ComponentDescriptor descriptor : bundledIndex().getComponents()) {
            // A consumer-facing displayName is required, but it may legitimately coincide with the
            // id for components whose simplest name/version IS already clean (e.g. "dxvk",
            // "turnip") -- the product rule is "no implementation-detail jargon", not "must differ
            // from the id". So we only assert the surrounding identity fields are populated.
            assertFalse(descriptor.getDisplayName().trim().isEmpty());
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
        assertTrue(glibcContainers.contains("glibc_wine_9.3-vanilla"));
        assertEquals("recommended Wine must be offered first",
            "glibc_wine_9.3-vanilla", glibcContainers.get(0));
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
            index.find("glibc_prefix-apps_3").get().getType());
        assertFalse(index.find("prefix-apps").isPresent());
        assertFalse(index.find("mono").isPresent());
        assertFalse(index.find("gecko").isPresent());
        assertFalse(index.find("physx").isPresent());
        assertFalse(index.find("fonts").isPresent());
    }
}
