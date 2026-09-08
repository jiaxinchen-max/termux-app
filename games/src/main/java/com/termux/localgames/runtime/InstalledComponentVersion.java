package com.termux.localgames.runtime;

import java.util.Locale;

public final class InstalledComponentVersion {
    private final int version;
    private final String sha256;

    public InstalledComponentVersion(int version, String sha256) {
        String normalized = sha256 == null ? "" : sha256.toLowerCase(Locale.US);
        if (version < 1 || !normalized.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid installed component identity");
        }
        this.version = version;
        this.sha256 = normalized;
    }

    public int getVersion() { return version; }
    public String getSha256() { return sha256; }
}
