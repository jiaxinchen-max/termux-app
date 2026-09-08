package com.termux.localgames.data;

import com.termux.localgames.domain.Game;
import com.termux.localgames.artwork.GameArtworkReference;
import com.termux.localgames.importer.GameImportValidation;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** Private, one-file-per-game repository with same-directory atomic publication. */
public final class FileGameRepository implements GameRepository {

    private static final String SUFFIX = ".properties";
    private final File directory;

    public FileGameRepository(File directory) {
        this.directory = directory;
    }

    @Override
    public synchronized List<Game> list() throws IOException {
        if (!directory.exists()) return Collections.emptyList();
        File[] files = directory.listFiles((parent, name) -> name.endsWith(SUFFIX));
        if (files == null) throw new IOException("game_repository_unreadable");
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<Game> games = new ArrayList<>();
        for (File file : files) games.add(read(file));
        games.sort(Comparator.comparing(Game::getName, String.CASE_INSENSITIVE_ORDER)
            .thenComparing(Game::getId));
        return Collections.unmodifiableList(games);
    }

    @Override
    public synchronized Optional<Game> find(String gameId) throws IOException {
        File file = fileFor(gameId);
        return file.isFile() ? Optional.of(read(file)) : Optional.empty();
    }

    @Override
    public synchronized void save(Game game) throws IOException {
        GameImportValidation.requireSafeGamePaths(game.getExecutable(),
            game.getWorkingDirectory());
        requireOwnedArtwork(game);
        ensureDirectory();
        File target = fileFor(game.getId());
        File temporary = new File(directory, game.getId() + ".tmp");
        Properties properties = encode(game);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            properties.store(output, null);
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("game_repository_publish_failed");
        }
    }

    @Override
    public synchronized void delete(String gameId) throws IOException {
        File file = fileFor(gameId);
        if (file.exists() && !file.delete()) throw new IOException("game_repository_delete_failed");
    }

    private Game read(File file) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        requireExactKeys(properties);
        int schema = parseInt(properties, "schemaVersion");
        if (schema != Game.SCHEMA_VERSION) throw new IOException("unsupported_game_schema");
        int argumentCount = parseInt(properties, "arguments.count");
        if (argumentCount < 0 || argumentCount > 1024) throw new IOException("invalid_arguments_count");
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < argumentCount; index++) {
            arguments.add(required(properties, "argument." + index));
        }
        try {
            Game game = new Game(required(properties, "id"), required(properties, "name"),
                required(properties, "rootUri"), required(properties, "executable"),
                required(properties, "workingDirectory"), arguments,
                properties.getProperty("artworkUri", ""),
                Long.parseLong(required(properties, "lastPlayedAt")));
            GameImportValidation.requireSafeGamePaths(game.getExecutable(),
                game.getWorkingDirectory());
            requireOwnedArtwork(game);
            if (!file.getName().equals(game.getId() + SUFFIX)) {
                throw new IOException("game_id_file_mismatch");
            }
            return game;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_game_record", error);
        }
    }

    private static Properties encode(Game game) {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", Integer.toString(Game.SCHEMA_VERSION));
        properties.setProperty("id", game.getId());
        properties.setProperty("name", game.getName());
        properties.setProperty("rootUri", game.getRootUri());
        properties.setProperty("executable", game.getExecutable());
        properties.setProperty("workingDirectory", game.getWorkingDirectory());
        properties.setProperty("artworkUri", game.getArtworkUri());
        properties.setProperty("lastPlayedAt", Long.toString(game.getLastPlayedAt()));
        properties.setProperty("arguments.count", Integer.toString(game.getArguments().size()));
        for (int index = 0; index < game.getArguments().size(); index++) {
            properties.setProperty("argument." + index, game.getArguments().get(index));
        }
        return properties;
    }

    private static void requireExactKeys(Properties properties) throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList("schemaVersion", "id", "name",
            "rootUri", "executable", "workingDirectory", "artworkUri", "lastPlayedAt",
            "arguments.count"));
        int count = parseInt(properties, "arguments.count");
        if (count >= 0 && count <= 1024) {
            for (int index = 0; index < count; index++) allowed.add("argument." + index);
        }
        for (Object key : properties.keySet()) {
            if (!allowed.contains(key.toString())) throw new IOException("unknown_game_field");
        }
    }

    private File fileFor(String gameId) {
        if (gameId == null || !gameId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid_game_id");
        }
        return new File(directory, gameId + SUFFIX);
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("game_repository_create_failed");
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) throw new IOException("missing_game_field:" + key);
        return value;
    }

    private static int parseInt(Properties properties, String key) throws IOException {
        try {
            return Integer.parseInt(required(properties, key));
        } catch (NumberFormatException error) {
            throw new IOException("invalid_game_field:" + key, error);
        }
    }

    private static void requireOwnedArtwork(Game game) {
        if (!game.getArtworkUri().isEmpty() &&
            !GameArtworkReference.gameId(game.getArtworkUri())
                .filter(game.getId()::equals).isPresent()) {
            throw new IllegalArgumentException("invalid_game_artwork_reference");
        }
    }
}
