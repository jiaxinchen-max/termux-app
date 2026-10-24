package com.termux.ui.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class XServerPackageManifestTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    private static XServerPackageManifest parse(String text) throws IOException {
        return XServerPackageManifest.parse(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void parsesLinesInOrderAndIgnoresCommentsAndBlanks() throws Exception {
        XServerPackageManifest manifest = parse("# header\n\n"
            + "aarch64 first_1.0_aarch64.deb " + SHA_A + "\n"
            + "  aarch64   second_2.0_aarch64.deb   " + SHA_B + "  \n"
            + "x86_64 first_1.0_x86_64.deb " + SHA_A + "\n");

        assertEquals(2, manifest.entriesFor("arm64-v8a").size());
        assertEquals("first_1.0_aarch64.deb", manifest.entriesFor("arm64-v8a").get(0).getFile());
        assertEquals("second_2.0_aarch64.deb", manifest.entriesFor("arm64-v8a").get(1).getFile());
        assertEquals(SHA_B, manifest.entriesFor("arm64-v8a").get(1).getSha256());
        assertEquals(1, manifest.entriesFor("x86_64").size());
    }

    @Test
    public void anAbiWithoutLinesIsUnsupportedUntilTheyAreAdded() throws Exception {
        XServerPackageManifest manifest = parse("aarch64 a_1_aarch64.deb " + SHA_A + "\n");
        assertTrue(manifest.isSupported("arm64-v8a"));
        assertFalse(manifest.isSupported("armeabi-v7a"));
        assertFalse(manifest.isSupported("x86"));
        assertFalse(manifest.isSupported("x86_64"));
        assertFalse(manifest.isSupported("mips"));
        assertFalse(manifest.isSupported(null));
        assertTrue(manifest.entriesFor("mips").isEmpty());
    }

    @Test
    public void mapsAndroidAbisToTermuxArchitectures() {
        assertEquals("aarch64", XServerPackageManifest.termuxArchFor("arm64-v8a"));
        assertEquals("arm", XServerPackageManifest.termuxArchFor("armeabi-v7a"));
        assertEquals("x86_64", XServerPackageManifest.termuxArchFor("x86_64"));
        assertEquals("i686", XServerPackageManifest.termuxArchFor("x86"));
        assertNull(XServerPackageManifest.termuxArchFor("riscv64"));
        assertNull(XServerPackageManifest.termuxArchFor(null));
    }

    @Test
    public void rejectsMalformedLines() {
        assertThrows(IOException.class, () -> parse("aarch64 only-two-fields.deb\n"));
        assertThrows(IOException.class, () -> parse("sparc a_1.deb " + SHA_A + "\n"));
        assertThrows(IOException.class, () -> parse("aarch64 ../escape.deb " + SHA_A + "\n"));
        assertThrows(IOException.class, () -> parse("aarch64 not-a-deb.tar " + SHA_A + "\n"));
        assertThrows(IOException.class, () -> parse("aarch64 a_1.deb tooshort\n"));
        assertThrows(IOException.class, () -> parse("aarch64 a_1.deb " + "G".repeat(64) + "\n"));
    }

    @Test
    public void theShippedManifestIsValidAndCoversAarch64() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/local-games/x-server-packages.txt")) {
            assertNotNull("manifest asset must be on the test classpath", input);
            XServerPackageManifest manifest = XServerPackageManifest.parse(input);
            assertTrue(manifest.isSupported("arm64-v8a"));
            boolean hasServer = false;
            for (XServerPackageManifest.Entry entry : manifest.entriesFor("arm64-v8a")) {
                if (entry.getFile().startsWith("xorg-server_")) hasServer = true;
            }
            assertTrue("the X server package itself must be listed", hasServer);
        }
    }
}
