package com.termux.localgames.recovery;

public final class GameAssetUsage {

    private final GameAssetCategory category;
    private final long bytes;
    private final long files;
    private final boolean exists;
    private final boolean incomplete;
    private final boolean protectedExternal;

    public GameAssetUsage(GameAssetCategory category, long bytes, long files,
                          boolean exists, boolean incomplete, boolean protectedExternal) {
        if (category == null || bytes < 0 || files < 0) {
            throw new IllegalArgumentException("invalid asset usage");
        }
        this.category = category;
        this.bytes = bytes;
        this.files = files;
        this.exists = exists;
        this.incomplete = incomplete;
        this.protectedExternal = protectedExternal;
    }

    public GameAssetCategory getCategory() { return category; }
    public long getBytes() { return bytes; }
    public long getFiles() { return files; }
    public boolean exists() { return exists; }
    public boolean isIncomplete() { return incomplete; }
    public boolean isProtectedExternal() { return protectedExternal; }
}
