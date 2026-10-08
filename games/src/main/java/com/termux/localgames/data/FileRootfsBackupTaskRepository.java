package com.termux.localgames.data;

import com.termux.localgames.domain.RootfsBackupTask;
import com.termux.localgames.domain.RootfsBackupTaskState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

public final class FileRootfsBackupTaskRepository {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final PropertiesTaskStore<RootfsBackupTask> store;

    public FileRootfsBackupTaskRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        this.store = new PropertiesTaskStore<>(directory, new RootfsBackupTaskCodec(),
            ID_PATTERN, "Games RootFS backup task");
    }

    public void save(RootfsBackupTask task) throws IOException {
        store.save(task);
    }

    public Optional<RootfsBackupTask> find(String taskId) throws IOException {
        return store.find(taskId);
    }

    public List<RootfsBackupTask> list() throws IOException {
        List<RootfsBackupTask> result = new ArrayList<>(store.list());
        result.sort(Comparator.comparingLong(RootfsBackupTask::getCreatedAt));
        return result;
    }

    private static final class RootfsBackupTaskCodec
        implements PropertiesTaskStore.Codec<RootfsBackupTask> {
        @Override
        public String taskId(RootfsBackupTask task) {
            return task.getTaskId();
        }

        @Override
        public Properties toProperties(RootfsBackupTask task) {
            Properties value = new Properties();
            value.setProperty("schemaVersion", String.valueOf(RootfsBackupTask.SCHEMA_VERSION));
            value.setProperty("taskId", task.getTaskId());
            value.setProperty("kind", task.getKind().name());
            value.setProperty("state", task.getState().name());
            value.setProperty("errorCode", task.getErrorCode());
            value.setProperty("createdAt", String.valueOf(task.getCreatedAt()));
            value.setProperty("updatedAt", String.valueOf(task.getUpdatedAt()));
            return value;
        }

        @Override
        public RootfsBackupTask fromProperties(Properties value) throws IOException {
            try {
                if (!"1".equals(value.getProperty("schemaVersion"))) {
                    throw new IOException("backup_task_schema_unsupported");
                }
                return new RootfsBackupTask(required(value, "taskId"),
                    RootfsBackupTask.Kind.valueOf(required(value, "kind")),
                    RootfsBackupTaskState.valueOf(required(value, "state")),
                    value.getProperty("errorCode", ""),
                    Long.parseLong(required(value, "createdAt")),
                    Long.parseLong(required(value, "updatedAt")));
            } catch (IllegalArgumentException error) {
                throw new IOException("backup_task_invalid", error);
            }
        }

        private static String required(Properties value, String key) throws IOException {
            String result = value.getProperty(key);
            if (result == null || result.isEmpty()) {
                throw new IOException("backup_task_field_missing:" + key);
            }
            return result;
        }
    }
}
