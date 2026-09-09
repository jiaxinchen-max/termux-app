package com.termux.localgames.components.index;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.localgames.domain.GameRuntimeBackendType;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class ComponentIndexParserTest {

    @Test
    public void parsesBundledGameHubAlignedIndexWithoutChangingDelivery() throws Exception {
        InputStream input = getClass().getClassLoader()
            .getResourceAsStream("termux-box-packages/index-v1.json");
        assertTrue("bundled index must be available to tests", input != null);

        ComponentIndex index;
        try (InputStream closeable = input) {
            index = new ComponentIndexParser().parse(closeable);
        }

        assertEquals(2, index.getSchemaVersion());
        assertEquals("2026-09-03T00:00:00Z", index.getGeneratedAt());
        assertEquals(18, index.getComponents().size());
        ComponentDescriptor glibc = index.find("glibc-prefix").get();
        assertEquals("runtime", glibc.getCategory());
        assertEquals(ComponentType.RUNTIME_SUPPORT, glibc.getType());
        assertEquals("Termux GLIBC Runtime", glibc.getDisplayName());
        assertTrue(glibc.isBase());
        assertEquals(2, glibc.getVersion());
        assertEquals(56383776L, glibc.getSize());
        assertEquals("4c7edae8a58e6cd3f67e1ed6244c7a2eacb78978f2fa2d199d6314a15f22a902",
            glibc.getSha256());
        ComponentDescriptor source = index.find("hangover-11.9-debian13-source").get();
        assertEquals(ComponentType.IMAGE_FS, source.getType());
        assertTrue(source.supportsBackend(GameRuntimeBackendType.ROOTFS_PROOT));
        assertEquals(273571840L, source.getSize());
        assertEquals("896918679daa53d6d3a6a1c40132cd35a1d9edc7afdbc643f9bbb3e22b114348",
            source.getSha256());
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
            "\"base\":false,\"recommended\":true,\"profileValue\":\"" +
            profileValue + "\",\"runtimeBackends\":[\"glibc_termux_box\"]," +
            "\"version\":1,\"url\":\"https://example.invalid/a.tar.xz\"," +
            "\"size\":1,\"sha256\":\"" + SHA256 + "\"}";
    }

    private static final String SHA256 =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
}
