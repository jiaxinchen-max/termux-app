package com.termux.ui.data;

import com.termux.ui.domain.Game;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface GameRepository {
    List<Game> list() throws IOException;
    Optional<Game> find(String gameId) throws IOException;
    void save(Game game) throws IOException;
    void delete(String gameId) throws IOException;
}
