package com.termux.localgames.data;

import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

/** File-backed component task storage with recoverable same-directory replacement. */
public final class FileComponentTaskRepository implements ComponentTaskRepository {

    private static final int SCHEMA_VERSION = 1;
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]+");

    private final PropertiesTaskStore<ComponentTask> store;

    public FileComponentTaskRepository(File directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        ensureDirectory(directory);
        this.store = new PropertiesTaskStore<>(directory, new ComponentTaskCodec(), ID_PATTERN,
            "Games component task");
    }

    @Override
    public List<ComponentTask> list() throws IOException {
        List<ComponentTask> tasks = new ArrayList<>(store.list());
        tasks.sort(Comparator.comparing(ComponentTask::getTaskId));
        return tasks;
    }

    @Override
    public Optional<ComponentTask> find(String taskId) throws IOException {
        return store.find(taskId);
    }

    @Override
    public void save(ComponentTask task) throws IOException {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        store.save(task);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Unable to create component task directory");
        }
    }

    private static final class ComponentTaskCodec
        implements PropertiesTaskStore.Codec<ComponentTask> {
        @Override
        public String taskId(ComponentTask task) {
            return task.getTaskId();
        }

        @Override
        public Properties toProperties(ComponentTask task) {
            Properties properties = new Properties();
            properties.setProperty("schemaVersion", String.valueOf(SCHEMA_VERSION));
            properties.setProperty("taskId", task.getTaskId());
            properties.setProperty("packageName", task.getPackageName());
            properties.setProperty("version", String.valueOf(task.getVersion()));
            properties.setProperty("url", task.getUrl());
            properties.setProperty("expectedSize", String.valueOf(task.getExpectedSize()));
            properties.setProperty("sha256", task.getSha256());
            properties.setProperty("downloadedBytes", String.valueOf(task.getDownloadedBytes()));
            properties.setProperty("etag", task.getEtag());
            properties.setProperty("lastModified", task.getLastModified());
            properties.setProperty("state", task.getState().name());
            properties.setProperty("errorCode", task.getErrorCode());
            properties.setProperty("errorMessage", task.getErrorMessage());
            return properties;
        }

        @Override
        public ComponentTask fromProperties(Properties properties) throws IOException {
            try {
                int schemaVersion = Integer.parseInt(required(properties, "schemaVersion"));
                if (schemaVersion != SCHEMA_VERSION) {
                    throw new IOException("Unsupported component task schema: " + schemaVersion);
                }
                return new ComponentTask(
                    required(properties, "taskId"),
                    required(properties, "packageName"),
                    Integer.parseInt(required(properties, "version")),
                    required(properties, "url"),
                    Long.parseLong(required(properties, "expectedSize")),
                    required(properties, "sha256"),
                    Long.parseLong(required(properties, "downloadedBytes")),
                    properties.getProperty("etag", ""),
                    properties.getProperty("lastModified", ""),
                    ComponentTaskState.valueOf(required(properties, "state")),
                    properties.getProperty("errorCode", ""),
                    properties.getProperty("errorMessage", "")
                );
            } catch (RuntimeException e) {
                throw new IOException("Invalid component task properties", e);
            }
        }

        private static String required(Properties properties, String key) throws IOException {
            String value = properties.getProperty(key);
            if (value == null || value.trim().isEmpty()) {
                throw new IOException("Missing component task property: " + key);
            }
            return value;
        }
    }
}
