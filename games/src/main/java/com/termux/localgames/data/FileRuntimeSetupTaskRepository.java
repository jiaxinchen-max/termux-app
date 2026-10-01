package com.termux.localgames.data;

import com.termux.localgames.domain.RuntimeProvisionTask;
import com.termux.localgames.domain.RuntimeProvisionTaskState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

public final class FileRuntimeProvisionTaskRepository implements RuntimeProvisionTaskRepository {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final PropertiesTaskStore<RuntimeProvisionTask> store;

    public FileRuntimeProvisionTaskRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        this.store = new PropertiesTaskStore<>(directory, new RuntimeProvisionTaskCodec(),
            ID_PATTERN, "Games runtime provision task");
    }

    @Override
    public void save(RuntimeProvisionTask task) throws IOException {
        store.save(task);
    }

    @Override
    public Optional<RuntimeProvisionTask> find(String taskId) throws IOException {
        return store.find(taskId);
    }

    @Override
    public List<RuntimeProvisionTask> list() throws IOException {
        List<RuntimeProvisionTask> result = new ArrayList<>(store.list());
        result.sort(Comparator.comparingLong(RuntimeProvisionTask::getCreatedAt));
        return result;
    }

    private static final class RuntimeProvisionTaskCodec
        implements PropertiesTaskStore.Codec<RuntimeProvisionTask> {
        @Override
        public String taskId(RuntimeProvisionTask task) {
            return task.getTaskId();
        }

        @Override
        public Properties toProperties(RuntimeProvisionTask task) {
            Properties value = new Properties();
            value.setProperty("schemaVersion", String.valueOf(RuntimeProvisionTask.SCHEMA_VERSION));
            value.setProperty("taskId", task.getTaskId());
            value.setProperty("packageName", task.getPackageName());
            value.setProperty("version", String.valueOf(task.getVersion()));
            value.setProperty("recipeSha256", task.getRecipeSha256());
            value.setProperty("sourceComponentId", task.getSourceComponentId());
            value.setProperty("containerId", task.getContainerId());
            value.setProperty("containerName", task.getContainerName());
            value.setProperty("state", task.getState().name());
            value.setProperty("errorCode", task.getErrorCode());
            value.setProperty("createdAt", String.valueOf(task.getCreatedAt()));
            value.setProperty("updatedAt", String.valueOf(task.getUpdatedAt()));
            return value;
        }

        @Override
        public RuntimeProvisionTask fromProperties(Properties value) throws IOException {
            try {
                String schemaVersion = value.getProperty("schemaVersion");
                if (!"1".equals(schemaVersion) && !"2".equals(schemaVersion)) {
                    throw new IOException("provision_task_schema_unsupported");
                }
                return new RuntimeProvisionTask(required(value, "taskId"),
                    required(value, "packageName"), Integer.parseInt(required(value, "version")),
                    required(value, "recipeSha256"), required(value, "sourceComponentId"),
                    "2".equals(schemaVersion) ? required(value, "containerId") :
                        required(value, "containerName"),
                    required(value, "containerName"),
                    RuntimeProvisionTaskState.valueOf(required(value, "state")),
                    value.getProperty("errorCode", ""),
                    Long.parseLong(required(value, "createdAt")),
                    Long.parseLong(required(value, "updatedAt")));
            } catch (IllegalArgumentException error) {
                throw new IOException("provision_task_invalid", error);
            }
        }

        private static String required(Properties value, String key) throws IOException {
            String result = value.getProperty(key);
            if (result == null || result.isEmpty()) throw new IOException("provision_task_field_missing:" + key);
            return result;
        }
    }
}
