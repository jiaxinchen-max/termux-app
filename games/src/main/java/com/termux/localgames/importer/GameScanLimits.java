package com.termux.localgames.importer;

/** Hard bounds for one directory scan. */
public final class GameScanLimits {

    public static final GameScanLimits DEFAULT = new GameScanLimits(
        8, 20_000, 2_000, 256, 1L << 40);

    private final int maxDepth;
    private final int maxDocuments;
    private final int maxDirectories;
    private final int maxCandidates;
    private final long maxTotalBytes;

    public GameScanLimits(int maxDepth, int maxDocuments, int maxDirectories,
                          int maxCandidates, long maxTotalBytes) {
        if (maxDepth < 0) throw new IllegalArgumentException("maxDepth must not be negative");
        if (maxDocuments <= 0) throw new IllegalArgumentException("maxDocuments must be positive");
        if (maxDirectories <= 0) throw new IllegalArgumentException("maxDirectories must be positive");
        if (maxCandidates <= 0) throw new IllegalArgumentException("maxCandidates must be positive");
        if (maxTotalBytes <= 0) throw new IllegalArgumentException("maxTotalBytes must be positive");
        this.maxDepth = maxDepth;
        this.maxDocuments = maxDocuments;
        this.maxDirectories = maxDirectories;
        this.maxCandidates = maxCandidates;
        this.maxTotalBytes = maxTotalBytes;
    }

    public int getMaxDepth() { return maxDepth; }
    public int getMaxDocuments() { return maxDocuments; }
    public int getMaxDirectories() { return maxDirectories; }
    public int getMaxCandidates() { return maxCandidates; }
    public long getMaxTotalBytes() { return maxTotalBytes; }
}
