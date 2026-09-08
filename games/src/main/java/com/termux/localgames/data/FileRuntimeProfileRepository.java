package com.termux.localgames.data;

import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.GameRuntimeBackendType;

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

/** Strict one-file-per-game RuntimeProfile storage with an independent success snapshot. */
public final class FileRuntimeProfileRepository implements RuntimeProfileRepository {

    private static final String CURRENT_SUFFIX = ".properties";
    private static final String SUCCESS_SUFFIX = ".last-success.properties";
    private static final int MAX_MAP_ENTRIES = 256;
    private final File directory;

    public FileRuntimeProfileRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory must not be null");
        this.directory = directory;
    }

    @Override
    public synchronized List<RuntimeProfile> list() throws IOException {
        if (!directory.exists()) return Collections.emptyList();
        File[] files = directory.listFiles((parent, name) ->
            name.endsWith(CURRENT_SUFFIX) && !name.endsWith(SUCCESS_SUFFIX));
        if (files == null) throw new IOException("runtime_profile_repository_unreadable");
        Arrays.sort(files, Comparator.comparing(File::getName));
        List<RuntimeProfile> profiles = new ArrayList<>();
        for (File file : files) profiles.add(read(file, CURRENT_SUFFIX));
        return Collections.unmodifiableList(profiles);
    }

    @Override
    public synchronized Optional<RuntimeProfile> find(String profileId) throws IOException {
        return find(profileId, CURRENT_SUFFIX);
    }

    @Override
    public synchronized Optional<RuntimeProfile> findLastSuccessful(String profileId)
        throws IOException {
        return find(profileId, SUCCESS_SUFFIX);
    }

    @Override
    public synchronized void save(RuntimeProfile profile) throws IOException {
        save(profile, CURRENT_SUFFIX);
    }

    @Override
    public synchronized void saveLastSuccessful(RuntimeProfile profile) throws IOException {
        save(profile, SUCCESS_SUFFIX);
    }

    @Override
    public synchronized void delete(String profileId) throws IOException {
        deleteFile(fileFor(profileId, CURRENT_SUFFIX));
        deleteFile(fileFor(profileId, SUCCESS_SUFFIX));
    }

    private Optional<RuntimeProfile> find(String profileId, String suffix) throws IOException {
        File file = fileFor(profileId, suffix);
        return file.isFile() ? Optional.of(read(file, suffix)) : Optional.empty();
    }

    private void save(RuntimeProfile profile, String suffix) throws IOException {
        ensureDirectory();
        File target = fileFor(profile.getId(), suffix);
        File temporary = new File(directory, profile.getId() + suffix + ".tmp");
        Properties properties = encode(profile);
        try (FileOutputStream output = new FileOutputStream(temporary)) {
            properties.store(output, null);
            output.getFD().sync();
        } catch (IOException error) {
            temporary.delete();
            throw error;
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("runtime_profile_publish_failed");
        }
    }

    private RuntimeProfile read(File file, String suffix) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        int environmentCount = count(properties, "environment.count");
        int componentCount = count(properties, "componentVersions.count");
        int schema = parseInt(properties, "schemaVersion");
        if (schema < 1 || schema > RuntimeProfile.SCHEMA_VERSION) {
            throw new IOException("unsupported_runtime_profile_schema");
        }
        requireExactKeys(properties, environmentCount, componentCount, schema);
        Map<String, String> environment = readMap(properties, "environment", environmentCount);
        Map<String, String> components = readMap(properties, "componentVersions", componentCount);
        try {
            RuntimeProfile profile = new RuntimeProfile(required(properties, "id"),
                required(properties, "winePackage"), required(properties, "graphicsDriver"),
                required(properties, "dxWrapper"), required(properties, "audioDriver"),
                required(properties, "resolution"), required(properties, "box64Preset"),
                environment, requiredAllowEmpty(properties, "inputProfileId"),
                schema >= 2
                    ? LaunchExecutionMode.fromStorageValue(required(properties, "launchExecutionMode"))
                    : LaunchExecutionMode.APP_SHELL,
                components,
                schema >= 3
                    ? GameRuntimeBackendType.fromStorageValue(required(properties, "runtimeBackendType"))
                    : GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                schema >= 3 ? requiredAllowEmpty(properties, "rootfsPackage") : "",
                schema >= 4 ? required(properties, "containerId") : "default");
            if (!file.getName().equals(profile.getId() + suffix)) {
                throw new IOException("runtime_profile_id_file_mismatch");
            }
            return profile;
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_runtime_profile", error);
        }
    }

    private static Properties encode(RuntimeProfile profile) {
        Properties properties = new Properties();
        properties.setProperty("schemaVersion", Integer.toString(RuntimeProfile.SCHEMA_VERSION));
        properties.setProperty("id", profile.getId());
        properties.setProperty("winePackage", profile.getWinePackage());
        properties.setProperty("graphicsDriver", profile.getGraphicsDriver());
        properties.setProperty("dxWrapper", profile.getDxWrapper());
        properties.setProperty("audioDriver", profile.getAudioDriver());
        properties.setProperty("resolution", profile.getResolution());
        properties.setProperty("box64Preset", profile.getBox64Preset());
        properties.setProperty("inputProfileId", profile.getInputProfileId());
        properties.setProperty("launchExecutionMode",
            profile.getLaunchExecutionMode().getStorageValue());
        properties.setProperty("runtimeBackendType",
            profile.getRuntimeBackendType().getStorageValue());
        properties.setProperty("rootfsPackage", profile.getRootfsPackage());
        properties.setProperty("containerId", profile.getContainerId());
        writeMap(properties, "environment", profile.getEnvironment());
        writeMap(properties, "componentVersions", profile.getComponentVersions());
        return properties;
    }

    private static void writeMap(Properties properties, String prefix, Map<String, String> map) {
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        properties.setProperty(prefix + ".count", Integer.toString(keys.size()));
        for (int index = 0; index < keys.size(); index++) {
            String key = keys.get(index);
            properties.setProperty(prefix + "." + index + ".key", key);
            properties.setProperty(prefix + "." + index + ".value", map.get(key));
        }
    }

    private static Map<String, String> readMap(Properties properties, String prefix, int count)
        throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 0; index < count; index++) {
            String key = required(properties, prefix + "." + index + ".key");
            String value = required(properties, prefix + "." + index + ".value");
            if (result.put(key, value) != null) throw new IOException("duplicate_runtime_profile_key");
        }
        return result;
    }

    private static void requireExactKeys(Properties properties, int envCount, int componentCount,
                                         int schema)
        throws IOException {
        Set<String> allowed = new HashSet<>(Arrays.asList("schemaVersion", "id", "winePackage",
            "graphicsDriver", "dxWrapper", "audioDriver", "resolution", "box64Preset",
            "inputProfileId", "environment.count", "componentVersions.count"));
        if (schema >= 2) allowed.add("launchExecutionMode");
        if (schema >= 3) {
            allowed.add("runtimeBackendType");
            allowed.add("rootfsPackage");
        }
        if (schema >= 4) allowed.add("containerId");
        addMapKeys(allowed, "environment", envCount);
        addMapKeys(allowed, "componentVersions", componentCount);
        for (Object key : properties.keySet()) {
            if (!allowed.contains(key.toString())) throw new IOException("unknown_runtime_profile_field");
        }
    }

    private static void addMapKeys(Set<String> keys, String prefix, int count) {
        for (int index = 0; index < count; index++) {
            keys.add(prefix + "." + index + ".key");
            keys.add(prefix + "." + index + ".value");
        }
    }

    private static int count(Properties properties, String key) throws IOException {
        int count = parseInt(properties, key);
        if (count < 0 || count > MAX_MAP_ENTRIES) throw new IOException("invalid_runtime_profile_count");
        return count;
    }

    private static int parseInt(Properties properties, String key) throws IOException {
        try {
            return Integer.parseInt(required(properties, key));
        } catch (NumberFormatException error) {
            throw new IOException("invalid_runtime_profile_field:" + key, error);
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isEmpty()) throw new IOException("missing_runtime_profile_field:" + key);
        return value;
    }

    private static String requiredAllowEmpty(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null) throw new IOException("missing_runtime_profile_field:" + key);
        return value;
    }

    private File fileFor(String id, String suffix) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid_runtime_profile_id");
        }
        return new File(directory, id + suffix);
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("runtime_profile_repository_create_failed");
        }
    }

    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("runtime_profile_delete_failed");
    }
}
