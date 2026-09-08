package com.termux.localgames.runtime;

import android.content.Context;

import androidx.annotation.Nullable;

import com.termux.localgames.data.GameStoragePaths;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

/**
 * Prepares a private job for the bundled Termux {@code tar} script.
 *
 * <p>Android's document providers expose streams, not POSIX paths, so Java only bridges a chosen
 * SAF document to a private temporary file. Archive creation, extraction, links, modes and tree
 * moves are all performed by the actual Termux shell session.</p>
 */
public final class RuntimeBackupManager {
    private static final long MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 128 * 1024;

    private final Context context;
    private final File filesDirectory;
    private final GameStoragePaths paths;

    public RuntimeBackupManager(Context context, File filesDirectory) {
        if (context == null || filesDirectory == null) {
            throw new IllegalArgumentException("backup_context_required");
        }
        this.context = context.getApplicationContext();
        this.filesDirectory = filesDirectory;
        paths = new GameStoragePaths(filesDirectory);
    }

    public Job prepareExport(RuntimeBackupType type) throws IOException {
        requireExportSources(type);
        Job job = createJob("export", type);
        writeJob(job);
        return job;
    }

    public Job prepareRestore(InputStream source) throws IOException {
        if (source == null) throw new IllegalArgumentException("backup_source_required");
        Job job = createJob("restore", null);
        copy(source, job.archive);
        writeJob(job);
        return job;
    }

    /** Returns null while the Termux tar process is still running. */
    @Nullable public Outcome readOutcome(Job job) throws IOException {
        if (job == null || !job.result.isFile()) return null;
        Properties properties = new Properties();
        try (InputStream input = new BufferedInputStream(new FileInputStream(job.result))) {
            properties.load(input);
        }
        String status = properties.getProperty("status", "");
        if ("success".equals(status)) {
            RuntimeBackupType type;
            try {
                type = RuntimeBackupType.fromStorageValue(properties.getProperty("runtimeType", ""));
            } catch (IllegalArgumentException error) {
                throw new IOException("runtime_backup_result_invalid", error);
            }
            return Outcome.success(type);
        }
        if ("failed".equals(status)) {
            String error = properties.getProperty("error", "runtime_backup_failed");
            return Outcome.failure(error.matches("[A-Za-z0-9._:-]{1,160}")
                ? error : "runtime_backup_failed");
        }
        throw new IOException("runtime_backup_result_invalid");
    }

    public void copyExportTo(Job job, OutputStream destination, @Nullable CopyProgressListener listener)
        throws IOException {
        if (job == null || destination == null) throw new IllegalArgumentException("backup_destination_required");
        if (!job.archive.isFile()) throw new IOException("runtime_backup_archive_missing");
        try (InputStream input = new BufferedInputStream(new FileInputStream(job.archive), BUFFER_SIZE);
             OutputStream output = new BufferedOutputStream(destination, BUFFER_SIZE)) {
            copy(input, output, job.archive.length(), listener);
            output.flush();
        }
    }

    public void cleanup(Job job) {
        if (job == null) return;
        try { deleteTree(job.directory); } catch (IOException ignored) { }
    }

    private Job createJob(String operation, @Nullable RuntimeBackupType type) throws IOException {
        File jobs = new File(new File(filesDirectory, "games/runtime-backups"), "jobs");
        if (!jobs.isDirectory() && !jobs.mkdirs()) throw new IOException("runtime_backup_job_directory_failed");
        String taskId = "runtime-backup-" + UUID.randomUUID();
        File directory = new File(jobs, taskId);
        if (!directory.mkdirs()) throw new IOException("runtime_backup_job_create_failed");
        return new Job(taskId, operation, type, directory,
            new File(directory, "runtime-backup.sh"), new File(directory, "job.properties"),
            new File(directory, "runtime.tar"), new File(directory, "result.properties"));
    }

    private void requireExportSources(RuntimeBackupType type) throws IOException {
        if (type == null) throw new IllegalArgumentException("runtime_type_required");
        requireDirectory(new File(filesDirectory, "games/components/install"),
            "runtime_component_receipts_missing");
        if (type == RuntimeBackupType.GLIBC) {
            requireDirectory(new File(paths.getTermuxPrefixDirectory(), "glibc"),
                "glibc_runtime_missing");
        } else {
            requireDirectory(paths.getRootfsRuntimeDirectory(), "rootfs_runtime_missing");
            requireDirectory(paths.getProotDistroContainersDirectory(), "rootfs_runtime_missing");
        }
    }

    private void writeJob(Job job) throws IOException {
        copyAsset("local-games/runtime_backup.sh", job.script);
        if (!job.script.setExecutable(true, true)) throw new IOException("runtime_backup_script_mode_failed");
        Properties spec = new Properties();
        spec.setProperty("operation", job.operation);
        if (job.type != null) spec.setProperty("runtimeType", job.type.getStorageValue());
        spec.setProperty("filesDirectory", filesDirectory.getCanonicalPath());
        spec.setProperty("jobDirectory", job.directory.getCanonicalPath());
        spec.setProperty("archive", job.archive.getCanonicalPath());
        spec.setProperty("result", job.result.getCanonicalPath());
        spec.setProperty("taskId", job.taskId);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(job.spec),
                 StandardCharsets.UTF_8)) {
            for (String name : spec.stringPropertyNames()) {
                writer.write(name + "='" + shellValue(spec.getProperty(name)) + "'\n");
            }
        }
    }

    private void copyAsset(String asset, File target) throws IOException {
        try (InputStream input = context.getAssets().open(asset);
             OutputStream output = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
            copy(input, output);
            output.flush();
        }
    }

    private static void copy(InputStream input, File target) throws IOException {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(target), BUFFER_SIZE)) {
            copy(input, output);
            output.flush();
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        copy(input, output, -1L, null);
    }

    private static void copy(InputStream input, OutputStream output, long expectedBytes,
                             @Nullable CopyProgressListener listener) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        long lastReported = -8L * 1024L * 1024L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (total > MAX_ARCHIVE_BYTES - read) throw new IOException("runtime_backup_size_limit");
            output.write(buffer, 0, read);
            total += read;
            if (listener != null && total - lastReported >= 8L * 1024L * 1024L) {
                lastReported = total;
                listener.onProgress(total, expectedBytes);
            }
        }
        if (listener != null) listener.onProgress(total, expectedBytes);
    }

    private static String shellValue(String value) {
        return value.replace("'", "'\\''");
    }

    private static void requireDirectory(File directory, String error) throws IOException {
        if (!directory.isDirectory()) throw new IOException(error);
    }

    private static void deleteTree(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("runtime_backup_cleanup_unreadable");
            for (File child : children) deleteTree(child);
        }
        if (!file.delete()) throw new IOException("runtime_backup_cleanup_failed");
    }

    public static final class Job {
        private final String taskId;
        private final String operation;
        @Nullable private final RuntimeBackupType type;
        private final File directory;
        private final File script;
        private final File spec;
        private final File archive;
        private final File result;

        private Job(String taskId, String operation, @Nullable RuntimeBackupType type,
                    File directory, File script, File spec, File archive, File result) {
            this.taskId = taskId;
            this.operation = operation;
            this.type = type;
            this.directory = directory;
            this.script = script;
            this.spec = spec;
            this.archive = archive;
            this.result = result;
        }

        public String getTaskId() { return taskId; }
        public File getScript() { return script; }
        public File getSpec() { return spec; }
        public File getDirectory() { return directory; }
        public boolean isExport() { return "export".equals(operation); }
    }

    public interface CopyProgressListener {
        void onProgress(long copiedBytes, long totalBytes);
    }

    public static final class Outcome {
        @Nullable private final RuntimeBackupType type;
        @Nullable private final String error;
        private Outcome(@Nullable RuntimeBackupType type, @Nullable String error) {
            this.type = type;
            this.error = error;
        }
        static Outcome success(RuntimeBackupType type) { return new Outcome(type, null); }
        static Outcome failure(String error) { return new Outcome(null, error); }
        public boolean isSuccess() { return error == null; }
        @Nullable public RuntimeBackupType getType() { return type; }
        @Nullable public String getError() { return error; }
    }
}
