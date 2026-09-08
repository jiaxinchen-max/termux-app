package com.termux.localgames.recovery;

/** Explicit private-data selection; external game/save content is always kept. */
public final class GameUninstallPlan {

    private final String gameId;
    private final boolean prefix;
    private final boolean cache;
    private final boolean logs;
    private final boolean configuration;

    private GameUninstallPlan(String gameId, boolean prefix, boolean cache, boolean logs,
                              boolean configuration) {
        if (gameId == null || !gameId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid_game_id");
        }
        this.gameId = gameId;
        this.prefix = prefix;
        this.cache = cache;
        this.logs = logs;
        this.configuration = configuration;
    }

    public static GameUninstallPlan keepPrivateAssets(String gameId) {
        return new GameUninstallPlan(gameId, false, false, false, false);
    }

    public GameUninstallPlan withSelections(boolean prefix, boolean cache, boolean logs,
                                            boolean configuration) {
        return new GameUninstallPlan(gameId, prefix, cache, logs, configuration);
    }

    public String getGameId() { return gameId; }
    public boolean removesPrefix() { return prefix; }
    public boolean removesCache() { return cache; }
    public boolean removesLogs() { return logs; }
    public boolean removesConfiguration() { return configuration; }
    public boolean keepsExternalContent() { return true; }
}
