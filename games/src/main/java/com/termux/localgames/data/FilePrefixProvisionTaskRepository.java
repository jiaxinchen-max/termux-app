package com.termux.localgames.data;

import com.termux.localgames.domain.PrefixProvisionTask;
import com.termux.localgames.domain.RuntimeProvisionTaskState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/** Atomic one-file-per-task persistence for GLIBC prefix initialization. */
public final class FilePrefixProvisionTaskRepository {
    private final File directory;

    public FilePrefixProvisionTaskRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        this.directory = directory;
    }

    public synchronized void save(PrefixProvisionTask task) throws IOException {
        ensureDirectory();
        Properties value = new Properties();
        value.setProperty("schemaVersion", Integer.toString(PrefixProvisionTask.SCHEMA_VERSION));
        value.setProperty("taskId", task.getTaskId());
        value.setProperty("gameId", task.getGameId());
        value.setProperty("winePackage", task.getWinePackage());
        value.setProperty("state", task.getState().name());
        value.setProperty("errorCode", task.getErrorCode());
        value.setProperty("createdAt", Long.toString(task.getCreatedAt()));
        value.setProperty("updatedAt", Long.toString(task.getUpdatedAt()));
        File target = file(task.getTaskId());
        File temporary = new File(target.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            value.store(output, "Games GLIBC prefix provision task");
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("prefix_provision_task_publish_failed");
        }
    }

    public synchronized Optional<PrefixProvisionTask> find(String taskId) throws IOException {
        File target = file(taskId);
        return target.isFile() ? Optional.of(read(target)) : Optional.empty();
    }

    public synchronized List<PrefixProvisionTask> list() throws IOException {
        if (!directory.isDirectory()) return Collections.emptyList();
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".properties"));
        if (files == null) throw new IOException("prefix_provision_task_list_failed");
        List<PrefixProvisionTask> result = new ArrayList<>();
        for (File file : files) result.add(read(file));
        result.sort(Comparator.comparingLong(PrefixProvisionTask::getCreatedAt));
        return Collections.unmodifiableList(result);
    }

    private PrefixProvisionTask read(File file) throws IOException {
        Properties value = new Properties();
        try (FileInputStream input = new FileInputStream(file)) { value.load(input); }
        try {
            if (!"1".equals(value.getProperty("schemaVersion"))) {
                throw new IOException("prefix_provision_task_schema_unsupported");
            }
            return new PrefixProvisionTask(required(value, "taskId"), required(value, "gameId"),
                required(value, "winePackage"),
                RuntimeProvisionTaskState.valueOf(required(value, "state")),
                value.getProperty("errorCode", ""),
                Long.parseLong(required(value, "createdAt")),
                Long.parseLong(required(value, "updatedAt")));
        } catch (IllegalArgumentException error) {
            throw new IOException("prefix_provision_task_invalid", error);
        }
    }

    private File file(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid taskId");
        }
        return new File(directory, taskId + ".properties");
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("prefix_provision_task_directory_failed");
        }
    }

    private static String required(Properties value, String key) throws IOException {
        String result = value.getProperty(key);
        if (result == null || result.isEmpty()) {
            throw new IOException("prefix_provision_task_field_missing:" + key);
        }
        return result;
    }
}
