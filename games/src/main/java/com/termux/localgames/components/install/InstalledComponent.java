package com.termux.localgames.components.install;

import java.io.File;

public final class InstalledComponent {
    private final String packageName;
    private final int version;
    private final String sha256;
    private final File directory;

    InstalledComponent(String packageName, int version, String sha256, File directory) {
        this.packageName = packageName;
        this.version = version;
        this.sha256 = sha256;
        this.directory = directory;
    }

    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getSha256() { return sha256; }
    public File getDirectory() { return directory; }
}
