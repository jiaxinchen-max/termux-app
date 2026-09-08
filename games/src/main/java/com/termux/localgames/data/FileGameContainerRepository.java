package com.termux.localgames.data;

import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeTranslator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/** One durable, validated properties file per isolated runtime container. */
public final class FileGameContainerRepository implements GameContainerRepository {

    private static final String SUFFIX = ".properties";
    private final File directory;

    public FileGameContainerRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("containerDirectoryRequired");
        this.directory = directory;
    }

    @Override public synchronized List<GameContainer> list() throws IOException {
        if (!directory.exists()) return Collections.emptyList();
        File[] files = directory.listFiles((parent, name) -> name.endsWith(SUFFIX));
        if (files == null) throw new IOException("container_repository_unreadable");
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<GameContainer> result = new ArrayList<>();
        for (File file : files) result.add(read(file));
        return Collections.unmodifiableList(result);
    }

    @Override public synchronized Optional<GameContainer> find(String containerId) throws IOException {
        File file = fileFor(containerId);
        return file.isFile() ? Optional.of(read(file)) : Optional.empty();
    }

    @Override public synchronized void save(GameContainer container) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("container_repository_create_failed");
        }
        File target = fileFor(container.getId());
        File temporary = new File(directory, container.getId() + SUFFIX + ".tmp");
        Properties properties = encode(container);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            properties.store(output, null);
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("container_publish_failed");
        }
    }

    @Override public synchronized void delete(String containerId) throws IOException {
        File file = fileFor(containerId);
        if (file.exists() && !file.delete()) throw new IOException("container_delete_failed");
    }

    private GameContainer read(File file) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { properties.load(input); }
        requireExactKeys(properties);
        try {
            GameContainer container = new GameContainer(required(properties, "id"),
                required(properties, "name"), GameRuntimeBackendType.fromStorageValue(
                    required(properties, "backendType")), requiredAllowEmpty(properties, "rootfsPackage"),
                RuntimeTranslator.fromStorageValue(required(properties, "translator")),
                required(properties, "winePackage"), required(properties, "graphicsDriver"),
                required(properties, "dxWrapper"), required(properties, "audioDriver"),
                required(properties, "resolution"), required(properties, "box64Preset"),
                readEnvironment(properties));
            if (!file.getName().equals(container.getId() + SUFFIX)) {
                throw new IOException("container_id_file_mismatch");
            }
            return container;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_container", error);
        }
    }

    private static Properties encode(GameContainer container) {
        Properties result = new Properties();
        result.setProperty("schemaVersion", Integer.toString(GameContainer.SCHEMA_VERSION));
        result.setProperty("id", container.getId());
        result.setProperty("name", container.getName());
        result.setProperty("backendType", container.getBackendType().getStorageValue());
        result.setProperty("rootfsPackage", container.getRootfsPackage());
        result.setProperty("translator", container.getTranslator().getStorageValue());
        result.setProperty("winePackage", container.getWinePackage());
        result.setProperty("graphicsDriver", container.getGraphicsDriver());
        result.setProperty("dxWrapper", container.getDxWrapper());
        result.setProperty("audioDriver", container.getAudioDriver());
        result.setProperty("resolution", container.getResolution());
        result.setProperty("box64Preset", container.getBox64Preset());
        List<String> keys = new ArrayList<>(container.getEnvironment().keySet());
        Collections.sort(keys);
        result.setProperty("environment.count", Integer.toString(keys.size()));
        for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index);
            result.setProperty("environment." + index + ".key", key);
            result.setProperty("environment." + index + ".value", container.getEnvironment().get(key));
        }
        return result;
    }

    private static Map<String, String> readEnvironment(Properties properties) throws IOException {
        int count;
        try { count = Integer.parseInt(required(properties, "environment.count")); }
        catch (NumberFormatException error) { throw new IOException("invalid_container_environment_count", error); }
        if (count < 0 || count > 256) throw new IOException("invalid_container_environment_count");
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String key = required(properties, "environment." + index + ".key");
            if (result.put(key, required(properties, "environment." + index + ".value")) != null) {
                throw new IOException("duplicate_container_environment_key");
            }
        }
        return result;
    }

    private static void requireExactKeys(Properties properties) throws IOException {
        if (!"1".equals(required(properties, "schemaVersion"))) {
            throw new IOException("unsupported_container_schema");
        }
        int count;
        try { count = Integer.parseInt(required(properties, "environment.count")); }
        catch (NumberFormatException error) { throw new IOException("invalid_container_environment_count", error); }
        Set<String> allowed = new HashSet<>(Arrays.asList("schemaVersion", "id", "name",
            "backendType", "rootfsPackage", "translator", "winePackage", "graphicsDriver",
            "dxWrapper", "audioDriver", "resolution", "box64Preset", "environment.count"));
        for (int index = 0; index < count; index++) {
            allowed.add("environment." + index + ".key");
            allowed.add("environment." + index + ".value");
        }
        for (Object key : properties.keySet()) {
            if (!allowed.contains(key.toString())) throw new IOException("unknown_container_field");
        }
    }

    private File fileFor(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalidContainerId");
        }
        return new File(directory, id + SUFFIX);
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty()) throw new IOException("missing_container_field:" + key);
        return value;
    }

    private static String requiredAllowEmpty(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) throw new IOException("missing_container_field:" + key);
        return value;
    }
}
