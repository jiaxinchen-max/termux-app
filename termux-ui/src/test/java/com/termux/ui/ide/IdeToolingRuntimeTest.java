package com.termux.ui.ide;

import com.termux.ui.components.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.jar.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 34})
public class IdeToolingRuntimeTest {
    private byte[] payload(String protocol) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Termux-Tooling-Protocol", protocol);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry("probe")); jar.write(new byte[1024]); jar.closeEntry();
        }
        return bytes.toByteArray();
    }
    private IdeToolingRuntime.Pin pin(byte[] bytes) throws Exception {
        StringBuilder sha = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) sha.append(String.format("%02x", b & 255));
        String properties = "schemaVersion=1\nprotocol=1\nversion=1\nsize=" + bytes.length + "\nsha256=" + sha
            + "\nurl=https://github.com/jiaxinchen-max/termux-app-packages/releases/download/ide-components/androidide-tooling.jar\n";
        return new IdeToolingRuntime.Pin(new ByteArrayInputStream(properties.getBytes(StandardCharsets.US_ASCII)));
    }
    private HttpConnectionFactory connection(byte[] bytes, int[] requests) {
        return url -> { requests[0]++; return new HttpURLConnection(url) {
            public void connect() { }
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public int getResponseCode() { return 200; }
            public String getHeaderField(String name) { return "Content-Length".equals(name) ? Integer.toString(bytes.length) : null; }
            public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        }; };
    }
    @Test public void downloadsPrivatelyThenReusesOfflineAndRepairsCorruptInstallation() throws Exception {
        File root = Files.createTempDirectory("ide-component-").toFile();
        byte[] bytes = payload("1"); IdeToolingRuntime.Pin pin = pin(bytes); int[] requests = {0};
        File installed = IdeToolingRuntime.prepare(root, pin, DownloadControl.CONTINUE, (n,t) -> {}, connection(bytes, requests));
        assertTrue(installed.getCanonicalPath().startsWith(new File(root, "install").getCanonicalPath() + File.separator));
        assertArrayEquals(bytes, Files.readAllBytes(installed.toPath())); assertEquals(1, requests[0]);
        HttpConnectionFactory offline = url -> { throw new IOException("Must not use network"); };
        assertEquals(installed, IdeToolingRuntime.prepare(root, pin, DownloadControl.CONTINUE, (n,t) -> {}, offline));
        Files.write(installed.toPath(), new byte[] {0});
        assertEquals(installed, IdeToolingRuntime.prepare(root, pin, DownloadControl.CONTINUE, (n,t) -> {}, offline));
        assertArrayEquals(bytes, Files.readAllBytes(installed.toPath()));
    }
    @Test public void rejectsUnknownProtocolEvenWithMatchingHashAndRejectsUnpinnedManifest() throws Exception {
        File root = Files.createTempDirectory("ide-component-").toFile(); byte[] bytes = payload("2");
        try { IdeToolingRuntime.prepare(root, pin(bytes), DownloadControl.CONTINUE, (n,t) -> {}, connection(bytes, new int[1])); fail(); }
        catch (IOException expected) { }
        assertEquals(0, new File(root, "install").list().length);
        try { new IdeToolingRuntime.Pin(new ByteArrayInputStream("version=1".getBytes(StandardCharsets.US_ASCII))); fail(); }
        catch (IOException expected) { }
    }
    @Test public void cancelledDownloadDoesNotInstallAndCanBeRetried() throws Exception {
        File root = Files.createTempDirectory("ide-component-").toFile(); byte[] bytes = payload("1");
        IdeToolingRuntime.Pin pin = pin(bytes); boolean[] cancelled = {false}; int[] requests = {0};
        try {
            IdeToolingRuntime.prepare(root, pin, () -> cancelled[0] ? DownloadControl.Decision.CANCEL : DownloadControl.Decision.CONTINUE,
                (n,t) -> { if (n > 0) cancelled[0] = true; }, connection(bytes, requests)); fail();
        } catch (InterruptedException expected) { }
        assertEquals(0, new File(root, "install").list().length);
        assertTrue(IdeToolingRuntime.prepare(root, pin, DownloadControl.CONTINUE, (n,t) -> {}, connection(bytes, requests)).isFile());
    }
}
