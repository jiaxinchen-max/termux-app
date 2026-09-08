package com.termux.localgames.data;

import com.termux.localgames.domain.Game;

import java.util.Objects;

public final class GameLibraryItem {

    private final Game game;
    private final GameAccessState accessState;

    public GameLibraryItem(Game game, GameAccessState accessState) {
        this.game = Objects.requireNonNull(game, "game");
        this.accessState = Objects.requireNonNull(accessState, "accessState");
    }

    public Game getGame() { return game; }
    public GameAccessState getAccessState() { return accessState; }
}
