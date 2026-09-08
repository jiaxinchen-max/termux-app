package com.termux.localgames.recovery;

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

    public long getPrivateBytes() {
        long total = 0;
        for (GameAssetUsage usage : usages) {
            if (usage.isProtectedExternal()) continue;
            if (Long.MAX_VALUE - total < usage.getBytes()) return Long.MAX_VALUE;
            total += usage.getBytes();
        }
        return total;
    }
}
