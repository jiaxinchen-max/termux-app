package com.termux.localgames.data;

import com.termux.localgames.domain.CustomComponentInstallTask;
import com.termux.localgames.domain.CustomComponentInstallTaskState;
import com.termux.localgames.domain.CustomRuntimeComponent;
import com.termux.localgames.domain.RuntimeTranslator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

public final class FileCustomComponentInstallTaskRepository {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final PropertiesTaskStore<CustomComponentInstallTask> store;

    public FileCustomComponentInstallTaskRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        this.store = new PropertiesTaskStore<>(directory, new CustomComponentInstallTaskCodec(),
            ID_PATTERN, "Games custom component install task");
    }

    public void save(CustomComponentInstallTask task) throws IOException {
        store.save(task);
    }

    public Optional<CustomComponentInstallTask> find(String taskId) throws IOException {
        return store.find(taskId);
    }

    public List<CustomComponentInstallTask> list() throws IOException {
        List<CustomComponentInstallTask> result = new ArrayList<>(store.list());
        result.sort(Comparator.comparingLong(CustomComponentInstallTask::getCreatedAt));
        return result;
    }

    private static final class CustomComponentInstallTaskCodec
        implements PropertiesTaskStore.Codec<CustomComponentInstallTask> {
        @Override
        public String taskId(CustomComponentInstallTask task) {
            return task.getTaskId();
        }

        @Override
        public Properties toProperties(CustomComponentInstallTask task) {
            Properties value = new Properties();
            value.setProperty("schemaVersion", String.valueOf(CustomComponentInstallTask.SCHEMA_VERSION));
            value.setProperty("taskId", task.getTaskId());
            value.setProperty("componentId", task.getComponentId());
            value.setProperty("kind", task.getKind().name());
            value.setProperty("translator",
                task.getTranslator() == null ? "" : task.getTranslator().getStorageValue());
            value.setProperty("displayName", task.getDisplayName());
            value.setProperty("sha256", task.getSha256());
            value.setProperty("state", task.getState().name());
            value.setProperty("errorCode", task.getErrorCode());
            value.setProperty("createdAt", String.valueOf(task.getCreatedAt()));
            value.setProperty("updatedAt", String.valueOf(task.getUpdatedAt()));
            return value;
        }

        @Override
        public CustomComponentInstallTask fromProperties(Properties value) throws IOException {
            try {
                if (!"1".equals(value.getProperty("schemaVersion"))) {
                    throw new IOException("custom_install_task_schema_unsupported");
                }
                CustomRuntimeComponent.Kind kind =
                    CustomRuntimeComponent.Kind.valueOf(required(value, "kind"));
                String translatorValue = value.getProperty("translator", "");
                RuntimeTranslator translator = translatorValue.isEmpty()
                    ? null : RuntimeTranslator.fromStorageValue(translatorValue);
                return new CustomComponentInstallTask(required(value, "taskId"),
                    required(value, "componentId"), kind, translator,
                    required(value, "displayName"), required(value, "sha256"),
                    CustomComponentInstallTaskState.valueOf(required(value, "state")),
                    value.getProperty("errorCode", ""),
                    Long.parseLong(required(value, "createdAt")),
                    Long.parseLong(required(value, "updatedAt")));
            } catch (IllegalArgumentException error) {
                throw new IOException("custom_install_task_invalid", error);
            }
        }

        private static String required(Properties value, String key) throws IOException {
            String result = value.getProperty(key);
            if (result == null || result.isEmpty()) {
                throw new IOException("custom_install_task_field_missing:" + key);
            }
            return result;
        }
    }
}
