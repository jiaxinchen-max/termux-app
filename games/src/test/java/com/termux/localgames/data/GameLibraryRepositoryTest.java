package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;

import com.termux.localgames.domain.Game;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class GameLibraryRepositoryTest {

    @Test
    public void combinesPersistedOrderingWithCurrentAccessWithoutMutatingGames() throws Exception {
        List<Game> games = Arrays.asList(game("a", "Alpha"), game("b", "Beta"));
        GameRepository repository = new MemoryRepository(games);
        GameLibraryRepository library = new GameLibraryRepository(repository,
            game -> game.getId().equals("a") ? GameAccessState.ACCESSIBLE
                : GameAccessState.PERMISSION_LOST);

        List<GameLibraryItem> items = library.load();

        assertEquals(2, items.size());
        assertEquals("a", items.get(0).getGame().getId());
        assertEquals(GameAccessState.ACCESSIBLE, items.get(0).getAccessState());
        assertEquals(GameAccessState.PERMISSION_LOST, items.get(1).getAccessState());
        assertEquals("content://tree/b", games.get(1).getRootUri());
    }

    private static Game game(String id, String name) {
        return new Game(id, name, "content://tree/" + id, "Game.exe", ".",
            new ArrayList<>(), "", 0);
    }

    private static final class MemoryRepository implements GameRepository {
        private final List<Game> games;
        MemoryRepository(List<Game> games) { this.games = games; }
        @Override public List<Game> list() { return games; }
        @Override public Optional<Game> find(String gameId) {
            return games.stream().filter(game -> game.getId().equals(gameId)).findFirst();
        }
        @Override public void save(Game game) throws IOException { throw new IOException(); }
        @Override public void delete(String gameId) throws IOException { throw new IOException(); }
    }
}
