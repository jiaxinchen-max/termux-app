package com.termux.ui.components;

import java.io.File;
import java.io.IOException;

/**
 * Best-effort local recovery for component downloads: a fixed, user-writable directory (see
 * {@code ComponentTaskForegroundService.requireCoordinator()}) that a user can manually drop
 * previously-downloaded component archives into, keyed by the archive's own filename (the last
 * path segment of the component's download URL) so no internal componentId/version naming
 * convention needs to be known. Cache hits use the archive basename and directory confinement.
 */
public final class LocalComponentCache {

    private static final int BUFFER_SIZE = 32 * 1024;

    private final File directory;

    public LocalComponentCache(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory must not be null");
        this.directory = directory;
    }

    /** Returns the matching local file, or null if the directory/file does not exist.
     *  Never recurses into subdirectories or follows a file outside the cache. */
    public File find(String url, String expectedSha256) {
        String filename = basename(url);
        if (filename.isEmpty()) return null;
        if (!directory.isDirectory()) return null;
        File candidate = new File(directory, filename);
        if (!candidate.isFile()) return null;
        try {
            if (!candidate.getCanonicalFile().equals(new File(directory.getCanonicalFile(), filename))) return null;
            return candidate;
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

}
