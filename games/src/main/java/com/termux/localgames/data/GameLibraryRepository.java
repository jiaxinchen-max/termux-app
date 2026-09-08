package com.termux.localgames.data;

import com.termux.localgames.domain.Game;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Combines persisted games with non-persisted current access state. */
public final class GameLibraryRepository {

    private final GameRepository gameRepository;
    private final GameAccessProbe accessProbe;

    public GameLibraryRepository(GameRepository gameRepository, GameAccessProbe accessProbe) {
        this.gameRepository = gameRepository;
        this.accessProbe = accessProbe;
    }

    public List<GameLibraryItem> load() throws IOException {
        List<GameLibraryItem> items = new ArrayList<>();
        for (Game game : gameRepository.list()) {
            items.add(new GameLibraryItem(game, accessProbe.check(game)));
        }
        return Collections.unmodifiableList(items);
    }
}
