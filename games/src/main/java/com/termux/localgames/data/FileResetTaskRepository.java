package com.termux.localgames.data;

import com.termux.localgames.domain.ResetTask;
import com.termux.localgames.domain.ResetTarget;
import com.termux.localgames.domain.ResetTaskState;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Pattern;

public final class FileResetTaskRepository implements ResetTaskRepository {
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final PropertiesTaskStore<ResetTask> store;

    public FileResetTaskRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        this.store = new PropertiesTaskStore<>(directory, new ResetTaskCodec(), ID_PATTERN,
            "Games runtime reset task");
    }

    @Override
    public void save(ResetTask task) throws IOException {
        store.save(task);
    }

    @Override
    public Optional<ResetTask> find(String taskId) throws IOException {
        return store.find(taskId);
    }

    @Override
    public List<ResetTask> list() throws IOException {
        List<ResetTask> result = new ArrayList<>(store.list());
        result.sort(Comparator.comparingLong(ResetTask::getCreatedAt));
        return result;
    }

    private static final class ResetTaskCodec implements PropertiesTaskStore.Codec<ResetTask> {
        @Override
        public String taskId(ResetTask task) {
            return task.getTaskId();
        }

        @Override
        public Properties toProperties(ResetTask task) {
            Properties value = new Properties();
            value.setProperty("schemaVersion", String.valueOf(ResetTask.SCHEMA_VERSION));
            value.setProperty("taskId", task.getTaskId());
            value.setProperty("target", task.getTarget().name());
            value.setProperty("resetKey", task.getResetKey());
            value.setProperty("state", task.getState().name());
            value.setProperty("errorCode", task.getErrorCode());
            value.setProperty("createdAt", String.valueOf(task.getCreatedAt()));
            value.setProperty("updatedAt", String.valueOf(task.getUpdatedAt()));
            return value;
        }

        @Override
        public ResetTask fromProperties(Properties value) throws IOException {
            try {
                if (!"1".equals(value.getProperty("schemaVersion"))) {
                    throw new IOException("reset_task_schema_unsupported");
                }
                // Legacy reset tasks may name a target that no longer exists as an enum constant
                // (e.g. ROOTFS, removed when "rebuild" replaced RootFS reset). Skip those quietly
                // instead of letting valueOf() throw a noisy wrapped stack that the task-store logs
                // on every list()/render.
                String target = required(value, "target");
                if (!isKnownTarget(target)) {
                    throw new IOException("reset_task_target_unsupported:" + target);
                }
                return new ResetTask(required(value, "taskId"),
                    ResetTarget.valueOf(target),
                    value.getProperty("resetKey", ""),
                    ResetTaskState.valueOf(required(value, "state")),
                    value.getProperty("errorCode", ""),
                    Long.parseLong(required(value, "createdAt")),
                    Long.parseLong(required(value, "updatedAt")));
            } catch (IllegalArgumentException error) {
                throw new IOException("reset_task_invalid", error);
            }
        }

        private static boolean isKnownTarget(String target) {
            for (ResetTarget known : ResetTarget.values()) {
                if (known.name().equals(target)) return true;
            }
            return false;
        }

        private static String required(Properties value, String key) throws IOException {
            String result = value.getProperty(key);
            if (result == null || result.isEmpty()) throw new IOException("reset_task_field_missing:" + key);
            return result;
        }
    }
}
