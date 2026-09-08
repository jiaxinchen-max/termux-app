package com.termux.localgames.data;

import com.termux.localgames.domain.Game;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

public interface GameRepository {
    List<Game> list() throws IOException;
    Optional<Game> find(String gameId) throws IOException;
    void save(Game game) throws IOException;
    void delete(String gameId) throws IOException;
}
