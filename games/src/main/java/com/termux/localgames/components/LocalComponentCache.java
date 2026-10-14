package com.termux.localgames.components;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Best-effort local recovery for component downloads: a fixed, user-writable directory (see
 * {@code ComponentTaskForegroundService.requireCoordinator()}) that a user can manually drop
 * previously-downloaded component archives into, keyed by the archive's own filename (the last
 * path segment of the component's download URL) so no internal componentId/version naming
 * convention needs to be known. A hit still requires a SHA-256 match against the component's
 * expected digest -- an unverifiable file is never trusted.
 */
public final class LocalComponentCache {

    private static final int BUFFER_SIZE = 32 * 1024;

    private final File directory;

    public LocalComponentCache(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory must not be null");
        this.directory = directory;
    }

    /** Returns the matching local file, or null if the directory/file/digest doesn't check out.
     *  Never recurses into subdirectories and never trusts an empty or malformed digest. */
    public File find(String url, String expectedSha256) {
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) return null;
        String filename = basename(url);
        if (filename.isEmpty()) return null;
        if (!directory.isDirectory()) return null;
        File candidate = new File(directory, filename);
        if (!candidate.isFile()) return null;
        try {
            return expectedSha256.equals(sha256(candidate)) ? candidate : null;
        } catch (IOException error) {
            return null;
        }
    }

    private static String basename(String url) {
        if (url == null) return "";
        String trimmed = url;
        int query = trimmed.indexOf('?');
        if (query >= 0) trimmed = trimmed.substring(0, query);
        int slash = trimmed.lastIndexOf('/');
        String name = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        return name.contains("..") ? "" : name;
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }
}
