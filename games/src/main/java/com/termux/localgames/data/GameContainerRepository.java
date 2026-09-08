package com.termux.localgames.data;

import com.termux.localgames.domain.GameContainer;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Persistent inventory of isolated PC runtime containers. */
public interface GameContainerRepository {
    List<GameContainer> list() throws IOException;
    Optional<GameContainer> find(String containerId) throws IOException;
    void save(GameContainer container) throws IOException;
    void delete(String containerId) throws IOException;
}
