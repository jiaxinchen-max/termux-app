package com.termux.ui.wine.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.runtime.RuntimeBackupSharedStorage;
import com.termux.ui.service.RuntimeInstallationGate;
import com.termux.ui.service.SetupPreparationLog;
import com.termux.ui.wine.data.*;
import com.termux.ui.wine.domain.*;
import com.termux.ui.wine.recovery.*;
import com.termux.ui.wine.service.WineCDriveBackupService;
import java.io.*;
import java.util.*;

/** Durable one-owner C-drive jobs. Interrupted operations are cleaned up, never replayed. */
public final class WineCDriveBackupTasks {
    public enum Operation { BACKUP, RESTORE }
    public static final class Snapshot {
        public final String taskId, gameId, status, error;
        Snapshot(Properties job) {
            taskId = job.getProperty("taskId", ""); gameId = job.getProperty("gameId", "");
            status = job.getProperty("status", "IDLE"); error = job.getProperty("error", "");
        }
    }
    private static String active;
    private static boolean executing;
    private static volatile Thread worker;
    private final Context context;
    private final GameStoragePaths paths;
    public WineCDriveBackupTasks(Context context) {
        this.context = context.getApplicationContext(); paths = new GameStoragePaths(this.context.getFilesDir());
    }
    private static File journal(File files) { return new File(files, "front/runtime/cdrive-backup.properties"); }
    private static Properties load(File files) throws IOException {
        Properties job = new Properties(); File journal = journal(files);
        if (journal.isFile()) try (InputStream input = new FileInputStream(journal)) { job.load(input); }
        return job;
    }
    private void save(Properties job) throws IOException { saveJournal(context.getFilesDir(), job); }
    private static void saveJournal(File files, Properties job) throws IOException {
        File file = journal(files);
        if (!file.getParentFile().isDirectory() && !file.getParentFile().mkdirs()) throw new IOException("cdrive_journal_failed");
        File temporary = new File(file + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) { job.store(output, null); output.getFD().sync(); }
        if (!temporary.renameTo(file)) throw new IOException("cdrive_journal_failed");
    }
    public static void stopForMutation(File files, String gameId) throws IOException {
        Thread running;
        synchronized (WineCDriveBackupTasks.class) {
            Properties job = load(files);
            RuntimeProfile profile = new FileRuntimeProfileRepository(new GameStoragePaths(files).getProfilesDirectory())
                .find(gameId).orElse(null);
            if (!gameId.equals(job.getProperty("gameId")) && (profile == null
                || !profile.getContainerId().equals(job.getProperty("containerId")))) return;
            if (!"RUNNING".equals(job.getProperty("status"))
                && !"true".equals(job.getProperty("cleanupPending"))) return;
            running = worker;
            if (running != null) running.interrupt();
            else {
                active = null;
                if ("true".equals(job.getProperty("cleanupPending"))) {
                    new WineCDriveArchive(new AndroidWineCDriveFileSystem()).recover(
                        new GameStoragePaths(files).getContainerPrefixDirectory(job.getProperty("containerId"), GameRuntimeBackendType.ROOTFS_PROOT),
                        new File(job.getProperty("archive")), job.getProperty("taskId"), operation(job) == Operation.RESTORE,
                        "true".equals(job.getProperty("committed")), job.getProperty("outputIdentity", ""));
                    job.remove("cleanupPending");
                }
                job.setProperty("status", "FAILED");
                job.setProperty("error", "runtime_edit_cancelled");
                saveJournal(files, job);
            }
        }
        if (running != null && running != Thread.currentThread()) {
            try { running.join(2000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException("runtime_force_stop_interrupted", error); }
            if (running.isAlive()) throw new IOException("runtime_force_stop_failed");
        }
    }

    public static void requireAvailable(File files) throws IOException {
        Properties job = load(files);
        if ("RUNNING".equals(job.getProperty("status")) || "true".equals(job.getProperty("cleanupPending")))
            throw new IOException("cdrive_backup_busy");
    }
    public Snapshot snapshot() throws IOException {
        return GameAssetMutationLock.call(() -> {
            Properties job = load(context.getFilesDir());
            if (("RUNNING".equals(job.getProperty("status")) || "true".equals(job.getProperty("cleanupPending")))
                && active == null && cleanupAttempts.add(job.getProperty("taskId"))) {
                active = job.getProperty("taskId");
                try { start(active); } catch (RuntimeException error) { active = null; throw new IOException("cdrive_service_failed", error); }
            }
            return new Snapshot(job);
        });
    }
    public String enqueue(String gameId, Operation operation, File archive) throws IOException {
        return GameAssetMutationLock.call(() -> {
            requireAvailable(context.getFilesDir());
            if (executing || active != null) throw new IOException("cdrive_backup_busy");
            RuntimeProfile profile = profile(gameId);
            requireOwnedPrefix(profile.getContainerId());
            try { RuntimeInstallationGate.requireResetSlot(context.getFilesDir(), "cdrive"); }
            catch (Exception error) { throw new IOException(error.getMessage(), error); }
            for (LaunchTask task : new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).list())
                if (!task.getState().isTerminal()) throw new IOException("cdrive_stop_games_first");
            for (RootfsBackupTask task : new FileRootfsBackupTaskRepository(paths.getBackupTasksDirectory()).list())
                if (!task.getState().isTerminal()) throw new IOException("cdrive_runtime_busy");
            requireArchive(archive);
            if (operation == Operation.BACKUP && archive.exists()) throw new IOException("cdrive_destination_exists");
            if (operation == Operation.RESTORE && !archive.isFile()) throw new IOException("cdrive_archive_missing");
            Properties job = new Properties();
            String task = "cdrive-" + UUID.randomUUID();
            job.setProperty("taskId", task); job.setProperty("gameId", gameId);
            job.setProperty("containerId", profile.getContainerId()); job.setProperty("wine", profile.getWinePackage());
            job.setProperty("operation", operation.name()); job.setProperty("archive", archive.getCanonicalPath());
            job.setProperty("status", "RUNNING"); job.setProperty("committed", "false");
            new SetupPreparationLog(context.getFilesDir(), task).append("C drive and Wine registry only; shared runtime and application configuration are excluded.");
            save(job); active = task; reservedTasks.add(task);
            try { start(task); }
            catch (RuntimeException error) {
                active = null; reservedTasks.remove(task); job.setProperty("status", "FAILED"); job.setProperty("error", "cdrive_service_failed"); save(job);
                throw new IOException("cdrive_service_failed", error);
            }
            return task;
        });
    }
    private RuntimeProfile profile(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9._-]{1,128}")) throw new IOException("cdrive_game_invalid");
        if (!new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent()) throw new IOException("game_not_found");
        RuntimeProfile profile = new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find(id)
            .orElseThrow(() -> new IOException("runtime_profile_missing"));
        if (profile.getRuntimeBackendType() != GameRuntimeBackendType.ROOTFS_PROOT
            || GameContainer.DEFAULT_ID.equals(profile.getContainerId())) throw new IOException("cdrive_proot_only");
        for (RuntimeProfile other : new FileRuntimeProfileRepository(paths.getProfilesDirectory()).list())
            if (!other.getId().equals(id) && other.getContainerId().equals(profile.getContainerId()))
                throw new IOException("cdrive_container_not_independent");
        return profile;
    }
    private static void requireArchive(File archive) throws IOException {
        if (!archive.getName().endsWith(WineCDriveArchive.EXTENSION)
            || !RuntimeBackupSharedStorage.isWithin(Environment.getExternalStorageDirectory(), archive))
            throw new IOException("cdrive_archive_path_invalid");
    }
    private void start(String task) {
        Intent intent = new Intent(context, WineCDriveBackupService.class).putExtra("taskId", task);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent); else context.startService(intent);
    }
    public void execute(String task) {
        Properties[] selected = {null}; boolean[] interrupted = {false};
        try {
            GameAssetMutationLock.call(() -> {
                synchronized (WineCDriveBackupTasks.class) {
                    Properties job = load(context.getFilesDir());
                    if (executing || !task.equals(job.getProperty("taskId"))
                        || (!"RUNNING".equals(job.getProperty("status")) && !"true".equals(job.getProperty("cleanupPending")))) return null;
                    interrupted[0] = !task.equals(active);
                    // snapshot() can schedule cleanup after process restart, so distinguish ownership
                    // by a process-local reservation made only by enqueue().
                    interrupted[0] |= !reservedTasks.remove(task);
                    executing = true; worker = Thread.currentThread(); selected[0] = job; return null;
                }
            });
            if (selected[0] == null) return;
            Properties job = selected[0];
            File prefix = requireOwnedPrefix(job.getProperty("containerId"));
            File archive = new File(job.getProperty("archive")); requireArchive(archive);
            WineCDriveArchive engine = new WineCDriveArchive(new AndroidWineCDriveFileSystem());
            boolean restore = operation(job) == Operation.RESTORE;
            if (interrupted[0]) {
                engine.recover(prefix, archive, task, restore, "true".equals(job.getProperty("committed")), job.getProperty("outputIdentity", ""));
                throw new IOException("cdrive_interrupted");
            }
            RuntimeProfile current = profile(job.getProperty("gameId"));
            if (!current.getContainerId().equals(job.getProperty("containerId")) || !current.getWinePackage().equals(job.getProperty("wine")))
                throw new IOException("cdrive_profile_changed");
            SetupPreparationLog log = new SetupPreparationLog(context.getFilesDir(), task);
            long[] last = {0};
            WineCDriveArchive.Progress progress = message -> {
                try {
                    if (message.startsWith("cdrive_output:")) {
                        GameAssetMutationLock.call(() -> { job.setProperty("outputIdentity", message.substring("cdrive_output:".length())); save(job); return null; });
                    } else if (message.equals("cdrive_committed")) {
                        GameAssetMutationLock.call(() -> { job.setProperty("committed", "true"); save(job); return null; });
                    } else if (System.currentTimeMillis() - last[0] > 250) {
                        log.append(message); last[0] = System.currentTimeMillis();
                    }
                } catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            };
            if (restore) engine.restore(prefix, archive, job.getProperty("wine"), task, progress);
            else engine.backup(prefix, archive, job.getProperty("wine"), task, progress);
            log.append("Completed: " + archive.getName());
            finish(job, "SUCCEEDED", "");
        } catch (Exception error) {
            if (selected[0] != null) {
                Thread.interrupted(); // Complete rollback before the mutation proceeds.
                Properties job = selected[0];
                String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                try {
                    new WineCDriveArchive(new AndroidWineCDriveFileSystem()).recover(
                        paths.getContainerPrefixDirectory(job.getProperty("containerId"), GameRuntimeBackendType.ROOTFS_PROOT),
                        new File(job.getProperty("archive")), task, operation(job) == Operation.RESTORE,
                        "true".equals(job.getProperty("committed")), job.getProperty("outputIdentity", ""));
                    job.remove("cleanupPending");
                } catch (IOException cleanup) { job.setProperty("cleanupPending", "true"); message += "; " + cleanup.getMessage(); }
                try { finish(job, "FAILED", message); } catch (IOException ignored) { /* A running journal forces recovery on the next attach. */ }
            }
        } finally {
            if (selected[0] != null) try { GameAssetMutationLock.call(() -> {
                if (task.equals(active)) active = null;
                executing = false; worker = null;
                return null;
            }); } catch (IOException ignored) { }
        }
    }
    private static final Set<String> reservedTasks = new HashSet<>();
    private static final Set<String> cleanupAttempts = new HashSet<>();
    private static Operation operation(Properties job) throws IOException {
        try { return Operation.valueOf(job.getProperty("operation", "")); }
        catch (IllegalArgumentException error) { throw new IOException("cdrive_operation_invalid", error); }
    }
    private File requireOwnedPrefix(String container) throws IOException {
        File directory = paths.getContainerDirectory(container);
        AndroidWineCDriveFileSystem fs = new AndroidWineCDriveFileSystem();
        if (fs.kind(directory) != 2) throw new IOException("cdrive_not_prepared");
        File prefix = paths.getContainerPrefixDirectory(container, GameRuntimeBackendType.ROOTFS_PROOT);
        File expected = new File(new File(paths.getContainersDirectory().getCanonicalFile(), container), prefix.getName());
        if (!expected.equals(prefix.getCanonicalFile()) || fs.kind(prefix) != 2) throw new IOException("cdrive_not_prepared");
        return prefix;
    }
    private void finish(Properties job, String status, String error) throws IOException {
        GameAssetMutationLock.call(() -> {
            job.setProperty("status", status); job.setProperty("error", error); save(job); return null;
        });
    }
}
