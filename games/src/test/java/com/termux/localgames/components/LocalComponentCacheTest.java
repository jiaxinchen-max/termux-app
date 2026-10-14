package com.termux.localgames.components;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public class LocalComponentCacheTest {

    private static final byte[] CONTENT = "hello world".getBytes(StandardCharsets.UTF_8);

    @Rule
    public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void findsMatchingFileByUrlBasename() throws Exception {
        File directory = temporaryFolder.newFolder();
        write(new File(directory, "box64-android-glibc-v11.tar.xz"), CONTENT);
        LocalComponentCache cache = new LocalComponentCache(directory);

        File found = cache.find(
            "https://github.com/example/releases/download/1.0/box64-android-glibc-v11.tar.xz",
            sha256(CONTENT));

        assertEquals(new File(directory, "box64-android-glibc-v11.tar.xz"), found);
    }

    @Test
    public void rejectsShaMismatch() throws Exception {
        File directory = temporaryFolder.newFolder();
        write(new File(directory, "wine.tar.xz"), CONTENT);
        LocalComponentCache cache = new LocalComponentCache(directory);

        assertNull(cache.find("https://example.invalid/wine.tar.xz",
            sha256("different".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    public void rejectsEmptyOrMalformedDigest() throws Exception {
        File directory = temporaryFolder.newFolder();
        write(new File(directory, "wine.tar.xz"), CONTENT);
        LocalComponentCache cache = new LocalComponentCache(directory);

        assertNull(cache.find("https://example.invalid/wine.tar.xz", ""));
        assertNull(cache.find("https://example.invalid/wine.tar.xz", "not-a-hash"));
    }

    @Test
    public void missingFileReturnsNull() throws Exception {
        File directory = temporaryFolder.newFolder();
        LocalComponentCache cache = new LocalComponentCache(directory);

        assertNull(cache.find("https://example.invalid/wine.tar.xz", sha256(CONTENT)));
    }

    @Test
    public void missingDirectoryReturnsNull() throws Exception {
        File directory = new File(temporaryFolder.newFolder(), "does-not-exist");
        LocalComponentCache cache = new LocalComponentCache(directory);

        assertNull(cache.find("https://example.invalid/wine.tar.xz", sha256(CONTENT)));
    }

    @Test
    public void rejectsPathTraversalInUrl() throws Exception {
        File directory = temporaryFolder.newFolder();
        File outside = temporaryFolder.newFile("secret.tar.xz");
        write(outside, CONTENT);
        LocalComponentCache cache = new LocalComponentCache(directory);

        assertNull(cache.find("https://example.invalid/../secret.tar.xz", sha256(CONTENT)));
    }

    private static void write(File file, byte[] value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value);
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder result = new StringBuilder();
        for (byte item : digest) {
            result.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return result.toString();
    }
}
