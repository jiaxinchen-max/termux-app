package com.termux.localgames.runtime;

public final class ComponentRequirement {
    private final String componentId;
    private final int version;
    private final String sha256;
    private final long downloadSize;

    public ComponentRequirement(String componentId, int version, String sha256,
                                long downloadSize) {
        this.componentId = componentId;
        this.version = version;
        this.sha256 = sha256;
        this.downloadSize = downloadSize;
    }

    public String getComponentId() { return componentId; }
    public int getVersion() { return version; }
    public String getSha256() { return sha256; }
    public long getDownloadSize() { return downloadSize; }
}
