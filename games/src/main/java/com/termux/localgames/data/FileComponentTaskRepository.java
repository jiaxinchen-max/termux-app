package com.termux.localgames.data;

import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/** File-backed component task storage with recoverable same-directory replacement. */
public final class FileComponentTaskRepository implements ComponentTaskRepository {

    private static final int SCHEMA_VERSION = 1;
    private static final String SUFFIX = ".properties";

    private final File directory;

    public FileComponentTaskRepository(File directory) throws IOException {
        if (directory == null) {
            throw new IllegalArgumentException("directory must not be null");
        }
        this.directory = directory;
        ensureDirectory(directory);
    }

    @Override
    public synchronized List<ComponentTask> list() throws IOException {
        recoverBackups();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(SUFFIX));
        if (files == null) {
            throw new IOException("Unable to list component task directory");
        }
        List<ComponentTask> tasks = new ArrayList<>(files.length);
        for (File file : files) {
            tasks.add(read(file));
        }
        tasks.sort(Comparator.comparing(ComponentTask::getTaskId));
        return tasks;
    }

    @Override
    public synchronized Optional<ComponentTask> find(String taskId) throws IOException {
        File file = fileFor(taskId);
        recoverBackupIfNeeded(file);
        return file.isFile() ? Optional.of(read(file)) : Optional.empty();
    }

    @Override
    public synchronized void save(ComponentTask task) throws IOException {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        File target = fileFor(task.getTaskId());
        File temporary = new File(target.getPath() + ".tmp");
        File backup = new File(target.getPath() + ".bak");
        deleteIfExists(temporary);
        write(temporary, task);

        deleteIfExists(backup);
        boolean hadTarget = target.isFile();
        if (hadTarget && !target.renameTo(backup)) {
            deleteIfExists(temporary);
            throw new IOException("Unable to stage existing component task");
        }
        if (!temporary.renameTo(target)) {
            if (hadTarget) {
                backup.renameTo(target);
            }
            deleteIfExists(temporary);
            throw new IOException("Unable to replace component task");
        }
        deleteIfExists(backup);
    }

    private ComponentTask read(File file) throws IOException {
        Properties properties = new Properties();
        try (InputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
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
            throw new IOException("Invalid component task file: " + file.getName(), e);
        }
    }

    private void write(File file, ComponentTask task) throws IOException {
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
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            properties.store(output, "Games component task");
            output.flush();
            output.getFD().sync();
        }
    }

    private File fileFor(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid taskId");
        }
        return new File(directory, taskId + SUFFIX);
    }

    private void recoverBackupIfNeeded(File target) throws IOException {
        File backup = new File(target.getPath() + ".bak");
        if (!target.exists() && backup.isFile() && !backup.renameTo(target)) {
            throw new IOException("Unable to recover component task backup");
        }
    }

    private void recoverBackups() throws IOException {
        File[] backups = directory.listFiles((dir, name) -> name.endsWith(SUFFIX + ".bak"));
        if (backups == null) {
            throw new IOException("Unable to inspect component task backups");
        }
        for (File backup : backups) {
            String targetPath = backup.getPath().substring(0, backup.getPath().length() - 4);
            File target = new File(targetPath);
            if (target.exists()) {
                deleteIfExists(backup);
            } else if (!backup.renameTo(target)) {
                throw new IOException("Unable to recover component task backup");
            }
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Missing component task property: " + key);
        }
        return value;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if ((!directory.isDirectory() && !directory.mkdirs()) || !directory.isDirectory()) {
            throw new IOException("Unable to create component task directory");
        }
    }

    private static void deleteIfExists(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to delete " + file.getName());
        }
    }
}
