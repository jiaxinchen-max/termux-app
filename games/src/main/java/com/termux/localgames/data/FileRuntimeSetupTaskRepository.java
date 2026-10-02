package com.termux.localgames.data;

import com.termux.localgames.domain.RuntimeSetupTask;
import com.termux.localgames.domain.RuntimeSetupTaskState;
import com.termux.localgames.runtime.RootfsSetupRecipe;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

public final class FileRuntimeSetupTaskRepository implements RuntimeSetupTaskRepository {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final PropertiesTaskStore<RuntimeSetupTask> store;

    public FileRuntimeSetupTaskRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        this.store = new PropertiesTaskStore<>(directory, new RuntimeSetupTaskCodec(),
            ID_PATTERN, "Games runtime setup task");
    }

    @Override
    public void save(RuntimeSetupTask task) throws IOException {
        store.save(task);
    }

    @Override
    public Optional<RuntimeSetupTask> find(String taskId) throws IOException {
        return store.find(taskId);
    }

    @Override
    public List<RuntimeSetupTask> list() throws IOException {
        List<RuntimeSetupTask> result = new ArrayList<>(store.list());
        result.sort(Comparator.comparingLong(RuntimeSetupTask::getCreatedAt));
        return result;
    }

    private static final class RuntimeSetupTaskCodec
        implements PropertiesTaskStore.Codec<RuntimeSetupTask> {
        @Override
        public String taskId(RuntimeSetupTask task) {
            return task.getTaskId();
        }

        @Override
        public Properties toProperties(RuntimeSetupTask task) {
            Properties value = new Properties();
            value.setProperty("schemaVersion", String.valueOf(RuntimeSetupTask.SCHEMA_VERSION));
            value.setProperty("taskId", task.getTaskId());
            value.setProperty("packageName", task.getPackageName());
            value.setProperty("version", String.valueOf(task.getVersion()));
            value.setProperty("recipeSha256", task.getRecipeSha256());
            value.setProperty("sourceComponentId", task.getSourceComponentId());
            value.setProperty("dxComponentId", task.getDxComponentId());
            value.setProperty("containerId", task.getContainerId());
            value.setProperty("containerName", task.getContainerName());
            value.setProperty("baseOnly", String.valueOf(task.isBaseOnly()));
            value.setProperty("state", task.getState().name());
            value.setProperty("errorCode", task.getErrorCode());
            value.setProperty("createdAt", String.valueOf(task.getCreatedAt()));
            value.setProperty("updatedAt", String.valueOf(task.getUpdatedAt()));
            return value;
        }

        @Override
        public RuntimeSetupTask fromProperties(Properties value) throws IOException {
            try {
                String schemaVersion = value.getProperty("schemaVersion");
                if (!"1".equals(schemaVersion) && !"2".equals(schemaVersion) &&
                    !"3".equals(schemaVersion) && !"4".equals(schemaVersion)) {
                    throw new IOException("setup_task_schema_unsupported");
                }
                return new RuntimeSetupTask(required(value, "taskId"),
                    required(value, "packageName"), Integer.parseInt(required(value, "version")),
                    required(value, "recipeSha256"), required(value, "sourceComponentId"),
                    "4".equals(schemaVersion) ? required(value, "dxComponentId") :
                        RootfsSetupRecipe.DEFAULT_DX_COMPONENT,
                    "1".equals(schemaVersion) ? required(value, "containerName") :
                        required(value, "containerId"),
                    required(value, "containerName"),
                    ("3".equals(schemaVersion) || "4".equals(schemaVersion)) &&
                        "true".equals(value.getProperty("baseOnly")),
                    RuntimeSetupTaskState.valueOf(required(value, "state")),
                    value.getProperty("errorCode", ""),
                    Long.parseLong(required(value, "createdAt")),
                    Long.parseLong(required(value, "updatedAt")));
            } catch (IllegalArgumentException error) {
                throw new IOException("setup_task_invalid", error);
            }
        }

        private static String required(Properties value, String key) throws IOException {
            String result = value.getProperty(key);
            if (result == null || result.isEmpty()) throw new IOException("setup_task_field_missing:" + key);
            return result;
        }
    }
}
