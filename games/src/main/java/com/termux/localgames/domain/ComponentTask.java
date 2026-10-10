package com.termux.localgames.domain;

import java.util.Locale;

/** Immutable persisted state for a resumable runtime-component download. */
public final class ComponentTask {

    public static final int SCHEMA_VERSION = 1;

    private final String taskId;
    private final String packageName;
    private final int version;
    private final String url;
    private final long expectedSize;
    private final String sha256;
    private final long downloadedBytes;
    private final String etag;
    private final String lastModified;
    private final ComponentTaskState state;
    private final String errorCode;
    private final String errorMessage;

    public ComponentTask(String taskId, String packageName, int version, String url,
                         long expectedSize, String sha256, long downloadedBytes,
                         String etag, String lastModified, ComponentTaskState state,
                         String errorCode, String errorMessage) {
        this.taskId = DomainValidation.requireText(taskId, "taskId");
        this.packageName = DomainValidation.requireText(packageName, "packageName");
        if (!packageName.matches("[A-Za-z0-9._-]+") || version < 1 || expectedSize < 1) {
            throw new IllegalArgumentException("invalid component identity or size");
        }
        this.version = version;
        this.url = DomainValidation.requireText(url, "url");
        // Empty sha256 means "not yet known" -- the two upstream-tracked wine components
        // (Hangover, which publishes no checksum at all) are resolved at rebuild time without a
        // pre-known digest; ComponentInstaller computes and records the real one once the
        // archive is downloaded (trust-on-first-download, see withVerifiedSha256()).
        String normalizedSha = DomainValidation.optionalText(sha256).toLowerCase(Locale.US);
        if (!normalizedSha.isEmpty() && !normalizedSha.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("sha256 must contain 64 lowercase hex characters");
        }
        if (downloadedBytes < 0 || downloadedBytes > expectedSize) {
            throw new IllegalArgumentException("downloadedBytes is outside expected size");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        if ((state == ComponentTaskState.VERIFIED ||
            state == ComponentTaskState.INSTALLING ||
            state == ComponentTaskState.INSTALLED) && downloadedBytes != expectedSize) {
            throw new IllegalArgumentException("verified or installed task requires all bytes");
        }
        this.expectedSize = expectedSize;
        this.sha256 = normalizedSha;
        this.downloadedBytes = downloadedBytes;
        this.etag = DomainValidation.optionalText(etag);
        this.lastModified = DomainValidation.optionalText(lastModified);
        this.state = state;
        this.errorCode = DomainValidation.optionalText(errorCode);
        this.errorMessage = DomainValidation.optionalText(errorMessage);
    }

    public static ComponentTask queued(String taskId, String packageName, int version,
                                       String url, long expectedSize, String sha256) {
        return new ComponentTask(taskId, packageName, version, url, expectedSize,
            sha256, 0, "", "", ComponentTaskState.QUEUED, "", "");
    }

    public ComponentTask transition(ComponentTaskState nextState, long nextDownloadedBytes,
                                    String nextEtag, String nextLastModified,
                                    String nextErrorCode, String nextErrorMessage) {
        if (state.isTerminal()) {
            throw new IllegalStateException("terminal component task cannot transition");
        }
        if (nextState == ComponentTaskState.QUEUED) {
            throw new IllegalArgumentException("component task cannot return to queued");
        }
        return new ComponentTask(taskId, packageName, version, url, expectedSize,
            sha256, nextDownloadedBytes, nextEtag, nextLastModified, nextState,
            nextErrorCode, nextErrorMessage);
    }

    /** Pins the digest actually computed from a downloaded archive once this task's sha256 was
     *  unknown at enqueue time (trust-on-first-download). Only meaningful going from an empty
     *  sha256 to a real one -- see ComponentInstaller.install(). */
    public ComponentTask withVerifiedSha256(String verifiedSha256) {
        return new ComponentTask(taskId, packageName, version, url, expectedSize,
            verifiedSha256, downloadedBytes, etag, lastModified, state,
            errorCode, errorMessage);
    }

    public String getTaskId() { return taskId; }
    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getUrl() { return url; }
    public long getExpectedSize() { return expectedSize; }
    public String getSha256() { return sha256; }
    public long getDownloadedBytes() { return downloadedBytes; }
    public String getEtag() { return etag; }
    public String getLastModified() { return lastModified; }
    public ComponentTaskState getState() { return state; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
}
