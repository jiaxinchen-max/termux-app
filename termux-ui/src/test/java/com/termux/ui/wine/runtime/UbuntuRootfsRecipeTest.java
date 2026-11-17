package com.termux.ui.wine.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

public class UbuntuRootfsRecipeTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();

    @Test public void legacyBaseCannotBecomeReadyThroughInstalledBox64() throws Exception {
        java.io.File root = temporary.newFolder();
        java.io.File etc = new java.io.File(root, "etc"); assertTrue(etc.mkdir());
        java.io.File manifest = new java.io.File(etc, "wine-runtime.properties");
        java.nio.file.Files.write(manifest.toPath(),
            "schemaVersion=3\nlayout=external-components-v1\nbaseImage=debian:trixie\nrecipeVersion=12\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThrows(java.io.IOException.class, () -> ProotPreparedRuntime.usesExternalLayout(root));
        java.nio.file.Files.write(manifest.toPath(),
            "schemaVersion=3\nlayout=external-components-v1\nbaseImage=ubuntu:resolute\nrecipeVersion=13\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertTrue(ProotPreparedRuntime.usesExternalLayout(root));
    }

    @Test public void newInstallSelectsUbuntuImageAndVersionedAssets() {
        RootfsSetupRecipe recipe = RootfsSetupRecipe.require("ubuntu-26.04-games-rootfs");
        assertEquals(13, recipe.getVersion());
        assertEquals("rootfs_ubuntu2604_hangover_pdb2_minimal", recipe.getSourceComponentId());
        assertEquals("local-games/runtime-rootfs-v13", recipe.getAssetDirectory());
        assertEquals(java.util.Collections.singletonList(recipe.getSourceComponentId()),
            recipe.getBaseComponentIds());
    }

    @Test public void historicalPackageAndRecipeAreRejected() {
        assertThrows(IllegalArgumentException.class,
            () -> RootfsSetupRecipe.require("debian-13-games-rootfs", 12));
        assertThrows(IllegalArgumentException.class,
            () -> RootfsSetupRecipe.require("ubuntu-26.04-games-rootfs", 12));
    }
}
