package com.termux.localgames.data;

import com.termux.localgames.domain.RuntimeProvisionTask;
import com.termux.localgames.domain.RuntimeProvisionTaskState;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

public final class FileRuntimeProvisionTaskRepository implements RuntimeProvisionTaskRepository {
    private final File directory;

    public FileRuntimeProvisionTaskRepository(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        this.directory = directory;
    }

    @Override
    public synchronized void save(RuntimeProvisionTask task) throws IOException {
        ensureDirectory(directory);
        Properties value = new Properties();
        value.setProperty("schemaVersion", String.valueOf(RuntimeProvisionTask.SCHEMA_VERSION));
        value.setProperty("taskId", task.getTaskId());
        value.setProperty("packageName", task.getPackageName());
        value.setProperty("version", String.valueOf(task.getVersion()));
        value.setProperty("recipeSha256", task.getRecipeSha256());
        value.setProperty("sourceComponentId", task.getSourceComponentId());
        value.setProperty("containerName", task.getContainerName());
        value.setProperty("state", task.getState().name());
        value.setProperty("errorCode", task.getErrorCode());
        value.setProperty("createdAt", String.valueOf(task.getCreatedAt()));
        value.setProperty("updatedAt", String.valueOf(task.getUpdatedAt()));
        File target = file(task.getTaskId());
        File temporary = new File(target.getPath() + ".tmp");
        File backup = new File(target.getPath() + ".bak");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            value.store(output, "Games runtime provision task");
            output.flush();
            output.getFD().sync();
        }
        if (backup.exists() && !backup.delete()) throw new IOException("provision_task_backup_failed");
        boolean hadTarget = target.isFile();
        if (hadTarget && !target.renameTo(backup)) throw new IOException("provision_task_stage_failed");
        if (!temporary.renameTo(target)) {
            if (hadTarget) backup.renameTo(target);
            throw new IOException("provision_task_publish_failed");
        }
        if (backup.exists() && !backup.delete()) throw new IOException("provision_task_backup_cleanup_failed");
    }

    @Override
    public synchronized Optional<RuntimeProvisionTask> find(String taskId) throws IOException {
        File file = file(taskId);
        recover(file);
        return file.isFile() ? Optional.of(read(file)) : Optional.empty();
    }

    @Override
    public synchronized List<RuntimeProvisionTask> list() throws IOException {
        if (!directory.isDirectory()) return Collections.emptyList();
        File[] backups = directory.listFiles((dir, name) -> name.endsWith(".properties.bak"));
        if (backups == null) throw new IOException("provision_task_list_failed");
        for (File backup : backups) {
            String targetName = backup.getName().substring(0, backup.getName().length() - 4);
            recover(new File(directory, targetName));
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files == null) throw new IOException("provision_task_list_failed");
        List<RuntimeProvisionTask> result = new ArrayList<>();
        for (File file : files) result.add(read(file));
        result.sort(Comparator.comparingLong(RuntimeProvisionTask::getCreatedAt));
        return Collections.unmodifiableList(result);
    }

    private RuntimeProvisionTask read(File file) throws IOException {
        Properties value = new Properties();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            value.load(input);
        }
        try {
            if (!"1".equals(value.getProperty("schemaVersion"))) {
                throw new IOException("provision_task_schema_unsupported");
            }
            return new RuntimeProvisionTask(required(value, "taskId"),
                required(value, "packageName"), Integer.parseInt(required(value, "version")),
                required(value, "recipeSha256"), required(value, "sourceComponentId"),
                required(value, "containerName"),
                RuntimeProvisionTaskState.valueOf(required(value, "state")),
                value.getProperty("errorCode", ""),
                Long.parseLong(required(value, "createdAt")),
                Long.parseLong(required(value, "updatedAt")));
        } catch (IllegalArgumentException error) {
            throw new IOException("provision_task_invalid", error);
        }
    }

    private File file(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid taskId");
        }
        return new File(directory, taskId + ".properties");
    }

    private static void recover(File target) throws IOException {
        File backup = new File(target.getPath() + ".bak");
        if (!target.exists() && backup.isFile() && !backup.renameTo(target)) {
            throw new IOException("provision_task_recovery_failed");
        }
    }

    private static String required(Properties value, String key) throws IOException {
        String result = value.getProperty(key);
        if (result == null || result.isEmpty()) throw new IOException("provision_task_field_missing:" + key);
        return result;
    }

    private static void ensureDirectory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("provision_task_directory_failed");
    }
}
