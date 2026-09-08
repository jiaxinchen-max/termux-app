package com.termux.localgames.artwork;

import java.util.Optional;

/** Stable logical reference; private absolute paths never enter Game records. */
public final class GameArtworkReference {

    private static final String PREFIX = "local-games://artwork/";

    private GameArtworkReference() {}

    public static String forGame(String gameId) {
        validateGameId(gameId);
        return PREFIX + gameId;
    }

    public static Optional<String> gameId(String reference) {
        if (reference == null || !reference.startsWith(PREFIX)) return Optional.empty();
        String gameId = reference.substring(PREFIX.length());
        try {
            validateGameId(gameId);
            return Optional.of(gameId);
        } catch (IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    public static void validateGameId(String gameId) {
        if (gameId == null || !gameId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid_game_id");
        }
    }
}
