package com.termux.ui.desktop.archive;

public final class DesktopArchiveLimits {
    public final long maxExpandedBytes;
    public final long maxArchiveBytes;
    public final int maxEntries;
    /** Bound on each control JSON, not a payload index or a file-size limit. */
    public final int maxIndexBytes;

    public DesktopArchiveLimits(long maxExpandedBytes, long maxArchiveBytes,
                                int maxEntries, int maxIndexBytes) {
        if (maxExpandedBytes < 1 || maxArchiveBytes < 1 || maxEntries < 1
                || maxIndexBytes < 1024) throw new IllegalArgumentException("invalid_archive_limits");
        this.maxExpandedBytes = maxExpandedBytes;
        this.maxArchiveBytes = maxArchiveBytes;
        this.maxEntries = maxEntries;
        this.maxIndexBytes = maxIndexBytes;
    }
}
