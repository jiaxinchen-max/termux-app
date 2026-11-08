package com.termux.ui.components.index;

import com.termux.ui.wine.domain.GameRuntimeBackendType;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ComponentIndexParserTest {

    @Test
    public void parsesBundledIndexWithPublishedPrebuiltRootfs() throws Exception {
        InputStream input = getClass().getClassLoader()
            .getResourceAsStream("termux-app-packages/index-v1.json");
        assertTrue("bundled index must be available to tests", input != null);

        ComponentIndex index;
        try (InputStream closeable = input) {
            index = new ComponentIndexParser().parse(closeable);
        }

        assertEquals(2, index.getSchemaVersion());
        assertEquals("2026-11-08T15:52:52Z", index.getGeneratedAt());
        assertEquals(25, index.getComponents().size());
        ComponentDescriptor image = index.find("rootfs_debian13_hangover_pdb2_minimal").get();
        assertEquals(ComponentType.IMAGE_FS, image.getType());
        assertTrue(image.isBase());
        assertTrue(image.isRecommended());
        assertFalse(image.isSelectable());
        assertTrue(image.supportsBackend(GameRuntimeBackendType.ROOTFS_PROOT));
        assertEquals(2, image.getVersion());
        assertEquals("https://github.com/jiaxinchen-max/termux-app-packages/releases/download/"
            + "rootfs-debian13-hangover-pdb2-minimal-v2/"
            + "rootfs-debian13-hangover-pdb2-minimal-v2.tar.zst", image.getUrl());
        assertEquals(323406172L, image.getSize());
        assertEquals("01b6fe94fc711db2e80ea69518e4ebfd613881dbed4d52bdd900b159221784b7",
            image.getSha256());
        assertFalse(index.find("termux-glibc").isPresent());
        ComponentDescriptor source = index.find("rootfs_hangover_11.16").get();
        assertEquals(ComponentType.RUNTIME_SUPPORT, source.getType());
        assertTrue(source.supportsBackend(GameRuntimeBackendType.ROOTFS_PROOT));
        assertEquals(2, source.getVersion());
        assertEquals("https://github.com/jiaxinchen-max/termux-app-packages/releases/download/"
            + "rootfs_hangover_11.16-v2/rootfs_hangover_11.16.tar", source.getUrl());
        assertEquals(100485120L, source.getSize());
        assertEquals("ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1",
            source.getSha256());
        ComponentDescriptor box64Rootfs = index.find("rootfs_box64_0.4.5").get();
        assertTrue(box64Rootfs.supportsBackend(GameRuntimeBackendType.ROOTFS_PROOT));
        assertEquals(ComponentType.TRANSLATOR, box64Rootfs.getType());
        // The three implementation-detail components are hidden from the user-facing list; a
        // representative selectable one is not.
        assertTrue(box64Rootfs.isHidden());
        assertTrue(index.find("rootfs_box64_0.4.5").get().isHidden());
        assertTrue(source.isHidden());
        assertFalse(index.find("rootfs_wine_10.0").get().isHidden());
        ComponentDescriptor rootfsDxvk = index.find("rootfs_dxvk_2.7").get();
        assertEquals(ComponentType.DX_WRAPPER, rootfsDxvk.getType());
        assertTrue(rootfsDxvk.supportsBackend(GameRuntimeBackendType.ROOTFS_PROOT));
        assertEquals("rootfs_dxvk_2.7", rootfsDxvk.getProfileValue());
        assertTrue(index.find("rootfs_dxvk_3.1").isPresent());
        assertTrue(index.find("rootfs_dxvk_1.10.3").isPresent());
        ComponentDescriptor boxProton = index.find("rootfs_proton_11.0").get();
        assertEquals(ComponentType.TRANSLATOR, boxProton.getType());
        assertTrue(boxProton.supportsBackend(GameRuntimeBackendType.ROOTFS_PROOT));
        assertEquals("rootfs_proton_11.0", boxProton.getProfileValue());
        assertEquals(2, boxProton.getVersion());
        assertEquals("a62eed7a78adb8b4edc2229e2e5f5c7a22e66eb7fcb13c25b27bd3667a7ffae6",
            boxProton.getSha256());
        for (String id : new String[]{"rootfs_prefix_hangover_pdb2", "rootfs_prefix_wine10_wow64",
                "rootfs_prefix_proton11_wow64"}) {
            ComponentDescriptor prefix = index.find(id).get();
            assertTrue(prefix.isHidden());
            assertFalse(prefix.isSelectable());
            assertEquals(ComponentType.RUNTIME_SUPPORT, prefix.getType());
            assertTrue(prefix.supportsBackend(GameRuntimeBackendType.ROOTFS_PROOT));
        }
    }

    @Test
    public void parsesSelectableMetadata() throws Exception {
        ComponentIndex index = new ComponentIndexParser().parse(new ByteArrayInputStream(
            index(packageValue("dxvk", "dx_wrapper", "dxvk"))
                .getBytes(StandardCharsets.UTF_8)));
        ComponentDescriptor descriptor = index.find("dxvk").get();
        assertEquals(ComponentType.DX_WRAPPER, descriptor.getType());
        assertEquals("DXVK", descriptor.getDisplayName());
        assertEquals(1, index.selectable(ComponentType.DX_WRAPPER,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX).size());
    }

    @Test
    public void selectableComponentsPutRecommendedVersionFirst() throws Exception {
        String older = packageValue("wine-9.0", "container", "wine-9.0")
            .replace("\"recommended\":true", "\"recommended\":false")
            .replace("\"displayName\":\"DXVK\"", "\"displayName\":\"Wine 9.0\"");
        String recommended = packageValue("wine-9.3", "container", "wine-9.3")
            .replace("\"displayName\":\"DXVK\"", "\"displayName\":\"Wine 9.3\"");
        ComponentIndex index = new ComponentIndexParser().parse(new ByteArrayInputStream(
            index(older + "," + recommended).getBytes(StandardCharsets.UTF_8)));

        assertEquals("wine-9.3", index.selectable(ComponentType.CONTAINER,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX).get(0).getId());
    }

    @Test
    public void rejectsSupersededAndUnknownSchemaVersions() throws Exception {
        // v1 is intentionally not accepted: the schema carries no product metadata and
        // guessing it from package ids is the historical baggage this change removed.
        expectInvalid(index(packageValue("dxvk", "dx_wrapper", "dxvk"))
            .replace("\"schemaVersion\":2", "\"schemaVersion\":1"));
        expectInvalid(index(packageValue("dxvk", "dx_wrapper", "dxvk"))
            .replace("\"schemaVersion\":2", "\"schemaVersion\":3"));
    }

    @Test
    public void rejectsIndexMissingProductMetadata() throws Exception {
        expectInvalid("{\"schemaVersion\":2,\"generatedAt\":\"now\",\"packages\":[" +
            "{\"id\":\"dxvk\",\"category\":\"runtime\",\"version\":1," +
            "\"url\":\"https://example.invalid/a.tar.xz\",\"size\":1,\"sha256\":\"" +
            SHA256 + "\"}]}");
    }

    @Test
    public void rejectsDuplicateComponentIds() throws Exception {
        expectInvalid(index(packageValue("same", "dx_wrapper", "same") + "," +
            packageValue("same", "container", "same")));
    }

    @Test
    public void rejectsNonHttpsSources() throws Exception {
        expectInvalid(index(packageValue("dxvk", "dx_wrapper", "dxvk")
            .replace("https://", "http://")));
    }

    @Test
    public void rejectsDebComponentsOutsideRootfsProot() throws Exception {
        expectInvalid(index(packageValue("box64", "translator", "box64")
            .replace("a.tar.xz", "box64.deb")));
    }

    @Test
    public void acceptsDebComponentsForRootfsProotOnly() throws Exception {
        String rootfsDeb = packageValue("box64", "translator", "box64")
            .replace("\"framework\":\"GLIBC\"", "\"framework\":\"ROOTFS\"")
            .replace("\"runtimeBackends\":[\"glibc_termux_box\"]",
                "\"runtimeBackends\":[\"rootfs_proot\"]")
            .replace("a.tar.xz", "box64.deb");
        ComponentIndex index = new ComponentIndexParser().parse(new ByteArrayInputStream(
            index(rootfsDeb).getBytes(StandardCharsets.UTF_8)));
        assertTrue(index.find("box64").get().supportsBackend(
            GameRuntimeBackendType.ROOTFS_PROOT));
    }

    @Test
    public void rejectsUnknownFieldsAndInvalidEnums() throws Exception {
        expectInvalid(index(packageValue("dxvk", "dx_wrapper", "dxvk"))
            .replace("\"packages\"", "\"unexpected\":true,\"packages\""));
        expectInvalid(index(packageValue("dxvk", "unknown", "dxvk")));
        expectInvalid(index(packageValue("dxvk", "dx_wrapper", "dxvk")
            .replace("glibc_termux_box", "unknown_backend")));
        expectInvalid(index(packageValue("dxvk", "dx_wrapper", "dxvk")
            .replace("[\"glibc_termux_box\"]",
                "[\"glibc_termux_box\",\"glibc_termux_box\"]")));
    }

    @Test
    public void rejectsMalformedShaAndDuplicateJsonKeys() throws Exception {
        expectInvalid(index(packageValue("dxvk", "dx_wrapper", "dxvk")
            .replace(SHA256, "bad")));
        expectInvalid("{\"schemaVersion\":2,\"schemaVersion\":2," +
            "\"generatedAt\":\"now\",\"packages\":[]}");
    }

    private static void expectInvalid(String json) throws Exception {
        try {
            new ComponentIndexParser().parse(new ByteArrayInputStream(
                json.getBytes(StandardCharsets.UTF_8)));
            fail("invalid index must be rejected");
        } catch (IOException expected) {
            // Expected strict schema rejection.
        }
    }

    private static String index(String packages) {
        return "{\"schemaVersion\":2,\"generatedAt\":\"now\",\"packages\":[" +
            packages + "]}";
    }

    private static String packageValue(String id, String type, String profileValue) {
        return "{\"id\":\"" + id + "\",\"category\":\"runtime\",\"type\":\"" + type +
            "\",\"displayName\":\"DXVK\",\"versionName\":\"2.0\"," +
            "\"summary\":\"Direct3D translation layer\",\"framework\":\"GLIBC\"," +
            "\"base\":false,\"recommended\":true,\"hidden\":false,\"profileValue\":\"" +
            profileValue + "\",\"runtimeBackends\":[\"glibc_termux_box\"]," +
            "\"version\":1,\"url\":\"https://example.invalid/a.tar.xz\"," +
            "\"size\":1,\"sha256\":\"" + SHA256 + "\"}";
    }

    private static final String SHA256 =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
}
