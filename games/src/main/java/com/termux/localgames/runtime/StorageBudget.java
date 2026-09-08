package com.termux.localgames.runtime;

public final class StorageBudget {
    private final long requiredBytes;
    private final long availableBytes;

    public StorageBudget(long requiredBytes, long availableBytes) {
        if (requiredBytes < 0 || availableBytes < 0) {
            throw new IllegalArgumentException("storage bytes must not be negative");
        }
        this.requiredBytes = requiredBytes;
        this.availableBytes = availableBytes;
    }

    public long getRequiredBytes() { return requiredBytes; }
    public long getAvailableBytes() { return availableBytes; }
    public boolean isSufficient() { return availableBytes >= requiredBytes; }
}
