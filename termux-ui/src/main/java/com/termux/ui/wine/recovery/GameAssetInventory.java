package com.termux.ui.wine.recovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class GameAssetInventory {

    private final String gameId;
    private final List<GameAssetUsage> usages;

    public GameAssetInventory(String gameId, List<GameAssetUsage> usages) {
        if (gameId == null || usages == null) throw new IllegalArgumentException("inventory required");
        this.gameId = gameId;
        this.usages = Collections.unmodifiableList(new ArrayList<>(usages));
    }

    public String getGameId() { return gameId; }
    public List<GameAssetUsage> getUsages() { return usages; }

    public long getSharedBytes() { return sum(true, false); }
    public long getContentBytes() { return sum(false, true); }
    public boolean isIncomplete() {
        for (GameAssetUsage usage : usages) if (usage.isIncomplete()) return true;
        return false;
    }
    private long sum(boolean shared, boolean external) {
        long total = 0;
        for (GameAssetUsage usage : usages) {
            if (usage.isShared() != shared || usage.isProtectedExternal() != external) continue;
            if (Long.MAX_VALUE - total < usage.getBytes()) return Long.MAX_VALUE;
            total += usage.getBytes();
        }
        return total;
    }

    public long getPrivateBytes() {
        long total = 0;
        for (GameAssetUsage usage : usages) {
            if (usage.isProtectedExternal() || usage.isShared()) continue;
            if (Long.MAX_VALUE - total < usage.getBytes()) return Long.MAX_VALUE;
            total += usage.getBytes();
        }
        return total;
    }
}
