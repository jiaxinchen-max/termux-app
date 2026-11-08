package com.termux.ui.wine.service;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import static org.junit.Assert.*;

public class PrebuiltPrefixAssetsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static String sha(byte[] data) throws Exception {
        StringBuilder s = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) s.append(String.format("%02x", b & 255));
        return s.toString();
    }

    private byte[] fixture(File source) throws Exception {
        byte[] manifest = "manifest".getBytes(StandardCharsets.UTF_8);
        byte[] delta = "delta".getBytes(StandardCharsets.UTF_8);
        byte[] bundle = ("{\"assets\":{\"manifest.json\":\"" + sha(manifest) +
            "\",\"delta.tgz\":\"" + sha(delta) + "\"}}").getBytes(StandardCharsets.UTF_8);
        Files.write(new File(source, "manifest.json").toPath(), manifest);
        Files.write(new File(source, "delta.tgz").toPath(), delta);
        Files.write(new File(source, "bundle.json").toPath(), bundle);
        return bundle;
    }

    @Test public void independentResourcesAreSelectedOnlyForTheirWine() {
        assertEquals("rootfs_prefix_hangover_pdb2", PrebuiltPrefixAssets.componentId("rootfs_hangover_11.16"));
        assertEquals("rootfs_prefix_wine10_wow64", PrebuiltPrefixAssets.componentId("rootfs_wine_10.0"));
        assertEquals("rootfs_prefix_proton11_wow64", PrebuiltPrefixAssets.componentId("rootfs_proton_11.0"));
        assertNull(PrebuiltPrefixAssets.componentId("custom-wine-example"));
    }

    @Test public void verifiedLocalResourceRestoresWithoutNetwork() throws Exception {
        File source = temporary.newFolder("source"), target = temporary.newFolder("target");
        PrebuiltPrefixAssets.copy(source, target, fixture(source));
        assertEquals("manifest", new String(Files.readAllBytes(new File(target, "manifest.json").toPath()), StandardCharsets.UTF_8));
        assertEquals("delta", new String(Files.readAllBytes(new File(target, "delta.tgz").toPath()), StandardCharsets.UTF_8));
        assertFalse(new File(target, "delta.tgz.pending").exists());
    }

    @Test(expected = IOException.class) public void changedBundleIsRejected() throws Exception {
        File source = temporary.newFolder("source"); byte[] bundle = fixture(source);
        Files.write(new File(source, "bundle.json").toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        PrebuiltPrefixAssets.copy(source, temporary.newFolder("target"), bundle);
    }

    @Test public void corruptSelectedPayloadCannotConsumeStaleRecipe() throws Exception {
        File source = temporary.newFolder("source"), target = temporary.newFolder("target");
        byte[] bundle = fixture(source);
        Files.write(new File(target, "manifest.json").toPath(), "old".getBytes(StandardCharsets.UTF_8));
        Files.write(new File(source, "delta.tgz").toPath(), "bad".getBytes(StandardCharsets.UTF_8));
        try { PrebuiltPrefixAssets.copy(source, target, bundle); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().startsWith("prefix_digest_mismatch")); }
        assertEquals("old", new String(Files.readAllBytes(new File(target, "manifest.json").toPath()), StandardCharsets.UTF_8));
    }

    @Test(expected = IOException.class) public void missingPayloadFails() throws Exception {
        File source = temporary.newFolder("source"); byte[] bundle = fixture(source);
        Files.delete(new File(source, "delta.tgz").toPath());
        PrebuiltPrefixAssets.copy(source, temporary.newFolder("target"), bundle);
    }

    @Test(expected = IOException.class) public void sourceSymlinkEscapeFails() throws Exception {
        File source = temporary.newFolder("source"); byte[] bundle = fixture(source);
        File outside = temporary.newFile("outside");
        Files.copy(new File(source, "delta.tgz").toPath(), outside.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.delete(new File(source, "delta.tgz").toPath());
        Files.createSymbolicLink(new File(source, "delta.tgz").toPath(), outside.toPath());
        PrebuiltPrefixAssets.copy(source, temporary.newFolder("target"), bundle);
    }

    @Test(expected = IOException.class) public void destinationSymlinkFails() throws Exception {
        File source = temporary.newFolder("source"), target = temporary.newFolder("target");
        byte[] bundle = fixture(source); File outside = temporary.newFile("outside");
        Files.createSymbolicLink(new File(target, "delta.tgz.pending").toPath(), outside.toPath());
        PrebuiltPrefixAssets.copy(source, target, bundle);
    }
}
