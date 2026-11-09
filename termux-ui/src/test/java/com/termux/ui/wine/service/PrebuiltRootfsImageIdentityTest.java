package com.termux.ui.wine.service;

import com.termux.ui.wine.runtime.RootfsSetupRecipe;
import org.junit.Test;
import java.io.IOException;
import java.util.Collections;
import static org.junit.Assert.*;

public class PrebuiltRootfsImageIdentityTest {
    private static final String SHA = new String(new char[64]).replace('\0', 'a');
    private static final String SOURCE = "ed206c70ca889b1bbbcedaa93d4018d315b3512a861dec551da8093f9a1807c1";
    private static final String IMAGE = "{\"imageId\":\"" + RootfsSetupRecipe.IMAGE_COMPONENT +
        "\",\"sourceComponentSha256\":\"" + SOURCE + "\",\"sha256\":\"" + SHA + "\"}";
    @Test public void imageAndWineSourceAreSeparate() throws Exception {
        RootfsSetupAssetInstaller.requireImageSource(IMAGE, new String[]{SHA});
        RootfsSetupRecipe recipe = RootfsSetupRecipe.require(RootfsSetupRecipe.DEFAULT_PACKAGE);
        assertEquals(12, recipe.getVersion());
        assertEquals(Collections.singletonList(RootfsSetupRecipe.IMAGE_COMPONENT), recipe.getBaseComponentIds());
        assertNotEquals(RootfsSetupRecipe.DEFAULT_SOURCE, recipe.getSourceComponentId());
    }
    @Test public void legacyRecipesAreRejectedBeforeReadingAssets() {
        for (int version : new int[]{10, 11}) {
            try {
                RootfsSetupRecipe.require(RootfsSetupRecipe.DEFAULT_PACKAGE, version);
                fail("obsolete recipe accepted: " + version);
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("rootfs_recipe_version_unsupported"));
            }
        }
    }
    @Test public void sourceDigestDoesNotGateImageMetadata() throws Exception {
        RootfsSetupAssetInstaller.requireImageSource(IMAGE, new String[]{SOURCE});
    }
    @Test public void changedImageDigestIsAccepted() throws Exception {
        RootfsSetupAssetInstaller.requireImageSource(IMAGE, new String[]{SHA.replace('a','b')});
    }
    @Test public void changedSourceDigestIsAccepted() throws Exception {
        RootfsSetupAssetInstaller.requireImageSource(IMAGE.replace(SOURCE,SHA), new String[]{SHA});
    }
    @Test(expected = IOException.class) public void malformedMetadataRejected() throws Exception {
        RootfsSetupAssetInstaller.requireImageSource("{}", new String[]{SHA});
    }
    @Test public void candidateCatalogMatchesPinnedAssetAndRetainsWineSource() throws Exception {
        java.nio.file.Path base = java.nio.file.Paths.get("src/main/assets/local-games/runtime-rootfs-v12");
        org.json.JSONObject image = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(base.resolve("image.json")), java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Path entryPath = java.nio.file.Paths.get("../distribution/termux-app-packages/prebuilt-rootfs-index-entry.json");
        String entry = new String(java.nio.file.Files.readAllBytes(entryPath), java.nio.charset.StandardCharsets.UTF_8);
        String json = "{\"schemaVersion\":2,\"generatedAt\":\"candidate\",\"packages\":[" + entry + "]}";
        com.termux.ui.components.index.ComponentDescriptor descriptor = new com.termux.ui.components.index.ComponentIndexParser()
            .parse(new java.io.ByteArrayInputStream(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .find(RootfsSetupRecipe.IMAGE_COMPONENT).get();
        assertEquals(image.getString("sha256"), descriptor.getSha256());
        assertEquals(image.getLong("bytes"), descriptor.getSize());
        assertTrue(descriptor.getUrl().endsWith("/" + image.getString("file")));
        assertEquals(com.termux.ui.components.index.ComponentType.IMAGE_FS, descriptor.getType());
        org.json.JSONObject bundle = new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(base.resolve("prefix-hangover-pdb2/bundle.json")), java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(SOURCE, bundle.getString("sourceComponentSha256"));
        requireImageSource(image.toString(), descriptor.getSha256());
    }
    private static void requireImageSource(String metadata, String sha) throws Exception {
        RootfsSetupAssetInstaller.requireImageSource(metadata, new String[]{sha});
    }
}
