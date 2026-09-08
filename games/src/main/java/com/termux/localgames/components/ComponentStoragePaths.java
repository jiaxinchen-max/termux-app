package com.termux.localgames.components;

import java.io.File;

/** Stable private-storage layout shared by component workers and read-only UI repositories. */
public final class ComponentStoragePaths {

    private final File root;
    private final File downloadsDirectory;

    public ComponentStoragePaths(File applicationFilesDirectory) {
        this(applicationFilesDirectory, null);
    }

    public ComponentStoragePaths(File applicationFilesDirectory, File retainedDownloadsDirectory) {
        if (applicationFilesDirectory == null) {
            throw new IllegalArgumentException("applicationFilesDirectory must not be null");
        }
        root = new File(applicationFilesDirectory, "games/components");
        downloadsDirectory = retainedDownloadsDirectory == null
            ? new File(root, "downloads") : retainedDownloadsDirectory;
    }

    public File getRoot() { return root; }
    public File getTasksDirectory() { return new File(root, "tasks"); }
    public File getDownloadsDirectory() { return downloadsDirectory; }
    public File getInstallDirectory() { return new File(root, "install"); }
}
