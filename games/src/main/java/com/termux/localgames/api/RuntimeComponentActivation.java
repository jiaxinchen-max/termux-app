package com.termux.localgames.api;

import java.io.File;

/**
 * Immutable request to publish a verified, safely extracted component into the host runtime.
 *
 * <p>Carries the delivery identity the host needs to record the install, so the games module
 * never has to know the host's on-disk layout or metadata format.
 */
public final class RuntimeComponentActivation {

    private final String componentId;
    private final int version;
    private final String category;
    private final String url;
    private final long size;
    private final String sha256;
    private final File preparedDirectory;

    public RuntimeComponentActivation(String componentId, int version, String category,
                                      String url, long size, String sha256,
                                      File preparedDirectory) {
        if (componentId == null || !componentId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid component id");
        }
        if (version < 1) throw new IllegalArgumentException("invalid component version");
        if (!("runtime".equals(category) || "wine".equals(category))) {
            throw new IllegalArgumentException("invalid component category");
        }
        if (size < 1) throw new IllegalArgumentException("invalid component size");
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid component sha256");
        }
        if (preparedDirectory == null || !preparedDirectory.isDirectory()) {
            throw new IllegalArgumentException("prepared directory must exist");
        }
        this.componentId = componentId;
        this.version = version;
        this.category = category;
        this.url = url == null ? "" : url;
        this.size = size;
        this.sha256 = sha256;
        this.preparedDirectory = preparedDirectory;
    }

    public String getComponentId() { return componentId; }
    public int getVersion() { return version; }
    public String getCategory() { return category; }
    public String getUrl() { return url; }
    public long getSize() { return size; }
    public String getSha256() { return sha256; }
    public File getPreparedDirectory() { return preparedDirectory; }
}
