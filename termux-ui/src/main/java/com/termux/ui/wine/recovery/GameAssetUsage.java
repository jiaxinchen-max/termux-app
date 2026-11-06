package com.termux.ui.wine.recovery;

public final class GameAssetUsage {

    private final GameAssetCategory category;
    private final long bytes;
    private final long files;
    private final boolean exists;
    private final boolean incomplete;
    private final boolean protectedExternal;
    private final boolean shared;

    public GameAssetUsage(GameAssetCategory category, long bytes, long files,
                          boolean exists, boolean incomplete, boolean protectedExternal) {
        this(category, bytes, files, exists, incomplete, protectedExternal, false);
    }

    public GameAssetUsage(GameAssetCategory category, long bytes, long files,
                          boolean exists, boolean incomplete, boolean protectedExternal, boolean shared) {
        if (category == null || bytes < 0 || files < 0) {
            throw new IllegalArgumentException("invalid asset usage");
        }
        this.category = category;
        this.bytes = bytes;
        this.files = files;
        this.exists = exists;
        this.incomplete = incomplete;
        this.protectedExternal = protectedExternal;
        this.shared = shared;
    }

    public GameAssetCategory getCategory() { return category; }
    public long getBytes() { return bytes; }
    public long getFiles() { return files; }
    public boolean exists() { return exists; }
    public boolean isIncomplete() { return incomplete; }
    public boolean isShared() { return shared; }
    public boolean isProtectedExternal() { return protectedExternal; }
}
