package com.termux.localgames.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.Game;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Properties;

public class FileGameRepositoryTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesUpdatesListsFindsAndDeletesRoundTrip() throws Exception {
        File directory = new File(temporaryFolder.getRoot(), "library");
        FileGameRepository repository = new FileGameRepository(directory);
        Game game = game("game-1", "Sample");

        repository.save(game);
        assertEquals(game.getExecutable(), repository.find("game-1").get().getExecutable());
        assertEquals(Arrays.asList("--name", "Player One"),
            repository.list().get(0).getArguments());

        repository.save(game("game-1", "Updated"));
        assertEquals("Updated", repository.find("game-1").get().getName());
        assertFalse(new File(directory, "game-1.tmp").exists());

        repository.delete("game-1");
        assertTrue(repository.list().isEmpty());
    }

    @Test(expected = IOException.class)
    public void rejectsUnknownPersistedFields() throws Exception {
        File directory = temporaryFolder.newFolder("library-corrupt");
        Properties properties = properties(game("game-1", "Sample"));
        properties.setProperty("unexpected", "value");
        try (FileOutputStream output = new FileOutputStream(
            new File(directory, "game-1.properties"))) {
            properties.store(output, null);
        }
        new FileGameRepository(directory).list();
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTraversalInGameId() throws Exception {
        new FileGameRepository(temporaryFolder.getRoot()).find("../outside");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsTraversalInGameExecutable() throws Exception {
        new FileGameRepository(temporaryFolder.getRoot()).save(new Game("game-1", "Sample",
            "content://tree/game", "../outside.exe", ".", Arrays.asList(), "", 0));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsArtworkOwnedByAnotherGame() throws Exception {
        new FileGameRepository(temporaryFolder.getRoot()).save(new Game("game-1", "Sample",
            "content://tree/game", "Game.exe", ".", Arrays.asList(),
            "local-games://artwork/game-2", 0));
    }

    private static Game game(String id, String name) {
        return new Game(id, name, "content://tree/game", "Sample.exe", ".",
            Arrays.asList("--name", "Player One"), "", 0);
    }

    private static Properties properties(Game game) {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", "1");
        properties.setProperty("id", game.getId());
        properties.setProperty("name", game.getName());
        properties.setProperty("rootUri", game.getRootUri());
        properties.setProperty("executable", game.getExecutable());
        properties.setProperty("workingDirectory", game.getWorkingDirectory());
        properties.setProperty("artworkUri", game.getArtworkUri());
        properties.setProperty("lastPlayedAt", "0");
        properties.setProperty("arguments.count", "2");
        properties.setProperty("argument.0", "--name");
        properties.setProperty("argument.1", "Player One");
        return properties;
    }
}
