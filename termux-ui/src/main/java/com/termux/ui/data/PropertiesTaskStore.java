package com.termux.ui.data;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Shared file-backed store for atomic replacement and legacy {@code .bak} recovery used by the
 * Reset/Component/RuntimeSetup task repositories. Each repository keeps its own
 * schema-versioning and migration quirks in its {@link Codec}; this class only owns the
 * directory scan and the tmp+fsync+rename dance.
 */
public final class PropertiesTaskStore<T> {
    private static final Logger LOG = Logger.getLogger("PropertiesTaskStore");

    public interface Codec<T> {
        String taskId(T task);
        Properties toProperties(T task);
        T fromProperties(Properties properties) throws IOException;
    }

    private final File directory;
    private final Codec<T> codec;
    private final Pattern idPattern;
    private final String storeComment;

    public PropertiesTaskStore(File directory, Codec<T> codec, Pattern idPattern,
                                String storeComment) {
        if (directory == null) throw new IllegalArgumentException("directory required");
        if (codec == null) throw new IllegalArgumentException("codec required");
        if (idPattern == null) throw new IllegalArgumentException("idPattern required");
        this.directory = directory;
        this.codec = codec;
        this.idPattern = idPattern;
        this.storeComment = storeComment;
    }

    public synchronized void save(T task) throws IOException {
        ensureDirectory(directory);
        Properties value = codec.toProperties(task);
        File target = file(codec.taskId(task));
        File temporary = File.createTempFile(target.getName() + ".", ".tmp", directory);
        File backup = new File(target.getPath() + ".bak");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary, false)) {
                value.store(output, storeComment);
                output.flush();
                output.getFD().sync();
            }
            // Same-directory rename atomically replaces the existing inode on Android/Linux.
            // Readers use separate store instances, so moving the old target away first
            // exposes an ENOENT window despite the instance-level synchronized methods.
            if (!temporary.renameTo(target)) throw new IOException("task_publish_failed");
        } finally {
            if (temporary.exists() && !temporary.delete()) {
                LOG.warning("unable to remove temporary task file: " + temporary.getName());
            }
        }
        if (backup.exists() && !backup.delete()) throw new IOException("task_backup_cleanup_failed");
    }

    public synchronized Optional<T> find(String taskId) throws IOException {
        File file = file(taskId);
        recover(file);
        return file.isFile() ? Optional.of(read(file)) : Optional.empty();
    }

    /**
     * Unlike a naive directory scan, a single file that fails to parse (e.g. a legacy or
     * corrupt record) is logged and excluded -- it does not fail the whole scan. This is the
     * fix for the reproduced bug where one stale {@code .properties} file made every caller
     * believe nothing was active.
     */
    public synchronized List<T> list() throws IOException {
        if (!directory.isDirectory()) return Collections.emptyList();
        File[] backups = directory.listFiles((dir, name) -> name.endsWith(".properties.bak"));
        if (backups == null) throw new IOException("task_list_failed");
        for (File backup : backups) {
            String targetName = backup.getName().substring(0, backup.getName().length() - 4);
            recover(new File(directory, targetName));
        }
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".properties"));
        if (files == null) throw new IOException("task_list_failed");
        List<T> result = new ArrayList<>();
        for (File file : files) {
            try {
                result.add(read(file));
            } catch (IOException error) {
                LOG.log(Level.WARNING, "skipping unreadable task file: " + file.getName(), error);
            }
        }
        return result;
    }

    private T read(File file) throws IOException {
        Properties value = new Properties();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            value.load(input);
        }
        return codec.fromProperties(value);
    }

    private File file(String taskId) {
        if (taskId == null || !idPattern.matcher(taskId).matches()) {
            throw new IllegalArgumentException("invalid taskId");
        }
        return new File(directory, taskId + ".properties");
    }

    private static void recover(File target) throws IOException {
        File backup = new File(target.getPath() + ".bak");
        if (!target.exists() && backup.isFile() && !backup.renameTo(target)) {
            throw new IOException("task_recovery_failed");
        }
    }

    private static void ensureDirectory(File file) throws IOException {
        if (!file.isDirectory() && !file.mkdirs()) throw new IOException("task_directory_failed");
    }
}
