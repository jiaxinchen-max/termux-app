package com.termux.localgames.runtime;

import java.io.File;

/** Immutable projection of a Games runtime built by Termux proot-distro. */
public final class RootfsRuntimeInstallation {
    private final String packageName;
    private final int version;
    private final String recipeSha256;
    private final String containerName;
    private final File rootfsDirectory;

    RootfsRuntimeInstallation(String packageName, int version, String recipeSha256,
                              String containerName, File rootfsDirectory) {
        this.packageName = packageName;
        this.version = version;
        this.recipeSha256 = recipeSha256;
        this.containerName = containerName;
        this.rootfsDirectory = rootfsDirectory;
    }

    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getRecipeSha256() { return recipeSha256; }
    public String getContainerName() { return containerName; }
    public File getRootfsDirectory() { return rootfsDirectory; }
}
