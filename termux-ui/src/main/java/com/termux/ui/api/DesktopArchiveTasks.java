package com.termux.ui.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.system.Os;
import android.system.OsConstants;

import com.termux.ui.data.FileDesktopDistroRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.archive.AndroidDesktopArchiveFileSystem;
import com.termux.ui.desktop.archive.DesktopArchiveEngine;
import com.termux.ui.desktop.archive.DesktopArchiveExportMode;
import com.termux.ui.desktop.archive.DesktopArchiveLimits;
import com.termux.ui.desktop.archive.DesktopArchiveMetadata;
import com.termux.ui.desktop.archive.DesktopArchiveImportResult;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopRunMode;
import com.termux.ui.domain.DesktopSessionState;
import com.termux.ui.platform.DesktopSessionOverrides;
import com.termux.ui.platform.OrientationPreference;
import com.termux.ui.runtime.RuntimeBackupSharedStorage;
import com.termux.ui.service.DesktopArchiveForegroundService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.UUID;

/** Durable single-owner export/import jobs. No archived host components or mode substitution. */
public final class DesktopArchiveTasks {
    public enum Status { IDLE, RUNNING, SUCCEEDED, FAILED }
    public static final class Snapshot {
        public final Status status;
        public final String taskId, message, log, desktopId;
        Snapshot(Status status, String taskId, String message, String log, String desktopId) {
            this.status = status; this.taskId = taskId; this.message = message;
            this.log = log; this.desktopId = desktopId;
        }
    }
    public interface Progress { void changed(Snapshot snapshot); }
    private static String activeTask;
    private final Context context;
    private final GameStoragePaths paths;
    private final File journal, containers;

    public DesktopArchiveTasks(Context context) {
        this.context = context.getApplicationContext();
        paths = new GameStoragePaths(this.context.getFilesDir());
        journal = new File(paths.getDesktopDirectory(), "archive-task.properties");
        containers = new File(this.context.getFilesDir(), "usr/var/lib/proot-distro/containers");
    }

    public static boolean isBusy() { return DesktopOperationGate.isArchiveActive(); }

    public void exportDesktop(String desktopId, File target) throws IOException {
        enqueue("export", desktopId, target);
    }
    public void importDesktop(File archive) throws IOException { enqueue("import", "", archive); }

    private void enqueue(String operation, String desktopId, File archive) throws IOException {
        synchronized (DesktopOperationGate.LOCK) {
            Snapshot previousSnapshot = snapshot(); // Never resubmit interrupted work.
            if ("journal".equals(previousSnapshot.taskId)) throw new IOException("desktop_archive_journal_failed");
            DesktopOperationGate.requireAvailable();
            DesktopRestorePrerequisites.requireIdle(context);
            Properties previous = load();
            if ("true".equals(previous.getProperty("cleanupPending"))) {
                startCleanup(previous);
                throw new IOException("desktop_archive_cleaning_interrupted");
            }
            if (!java.util.Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a")) {
                throw new IOException("desktop_archive_architecture_unsupported");
            }
            requireSharedArchive(archive);
            if ("export".equals(operation) && archive.exists()) {
                throw new IOException("desktop_archive_destination_exists");
            }
            if ("import".equals(operation) && !archive.isFile()) throw new IOException("desktop_archive_missing");
            requireDesktopsStopped();
            String archivePath = archive.getCanonicalPath();
            Properties job = new Properties();
            String token = UUID.randomUUID().toString();
            job.setProperty("taskId", token);
            job.setProperty("operation", operation);
            if ("export".equals(operation)) job.setProperty("exportMode", "exclusive-create");
            job.setProperty("archive", archivePath);
            job.setProperty("desktopId", desktopId);
            job.setProperty("newId", "desktop-import-" + token);
            job.setProperty("newAlias", "import-" + token);
            if ("import".equals(operation) && (new File(containers, "import-" + token).exists()
                || new File(containers, ".desktop-import-import-" + token).exists())) {
                throw new IOException("desktop_archive_destination_exists");
            }
            job.setProperty("status", Status.RUNNING.name());
            job.setProperty("message", "desktop_archive_preparing");
            job.setProperty("log", "");
            DesktopOperationGate.reserveArchive();
            try {
                save(job);
                activeTask = token;
                Intent intent = new Intent(context, DesktopArchiveForegroundService.class)
                    .putExtra("taskId", token);
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
                else context.startService(intent);
            } catch (Exception error) {
                activeTask = null;
                DesktopOperationGate.releaseArchive();
                job.setProperty("status", Status.FAILED.name());
                job.setProperty("message", "desktop_archive_service_start_failed");
                save(job);
                throw new IOException("desktop_archive_service_start_failed", error);
            }
        }
    }

    public Snapshot snapshot() {
        synchronized (DesktopOperationGate.LOCK) {
            try {
                Properties job = load();
                if (job.isEmpty()) return new Snapshot(Status.IDLE, "", "", "", "");
                if (Status.RUNNING.name().equals(job.getProperty("status")) && activeTask == null) {
                    startCleanup(job);
                }
                return view(job);
            } catch (Exception error) {
                return new Snapshot(Status.FAILED, "journal", "desktop_archive_journal_failed",
                    error.toString(), "");
            }
        }
    }

    private void startCleanup(Properties job) throws IOException {
        requireDesktopsStopped();
        DesktopOperationGate.reserveArchive();
        try {
            activeTask = job.getProperty("taskId");
            job.setProperty("interrupted", "true");
            job.setProperty("status", Status.RUNNING.name());
            job.setProperty("message", "desktop_archive_cleaning_interrupted");
            save(job);
            Intent intent = new Intent(context, DesktopArchiveForegroundService.class)
                .putExtra("taskId", activeTask);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (Exception error) {
            activeTask = null;
            DesktopOperationGate.releaseArchive();
            throw new IOException("desktop_archive_cleanup_start_failed", error);
        }
    }

    /** Called once by the non-sticky foreground service, on its worker thread. */
    public void execute(String taskId, Progress progress) {
        Properties job;
        synchronized (DesktopOperationGate.LOCK) {
            try {
                job = load();
                if (!taskId.equals(activeTask) || !taskId.equals(job.getProperty("taskId"))) return;
            } catch (IOException error) { activeTask = null; DesktopOperationGate.releaseArchive(); return; }
        }
        DesktopArchiveEngine engine = new DesktopArchiveEngine(new AndroidDesktopArchiveFileSystem(),
            new DesktopArchiveLimits(128L << 30, 128L << 30, 1000000, 1024 * 1024));
        DesktopArchiveImportResult imported = null;
        long[] lastUpdate = {0};
        try {
            if ("true".equals(job.getProperty("interrupted"))) {
                job.setProperty("cleanupPending", "true");
                save(job);
                cleanupInterrupted(job);
                job.remove("cleanupPending");
                throw new IOException("desktop_archive_interrupted");
            }
            File archive = new File(job.getProperty("archive"));
            requireSharedArchive(archive);
            com.termux.ui.desktop.archive.DesktopArchiveControl control = new com.termux.ui.desktop.archive.DesktopArchiveControl() {
                @Override public void createdOutput(File target, long device, long inode) throws IOException {
                    synchronized (DesktopOperationGate.LOCK) {
                        job.setProperty("outputDevice", Long.toString(device));
                        job.setProperty("outputInode", Long.toString(inode));
                        job.setProperty("outputOwned", "true");
                        save(job); // No payload bytes are written until ownership is durable.
                    }
                }
                @Override public void createdImportStaging(File directory, long device, long inode) throws IOException {
                    synchronized (DesktopOperationGate.LOCK) {
                        job.setProperty("importDevice", Long.toString(device));
                        job.setProperty("importInode", Long.toString(inode));
                        job.setProperty("importOwned", "true");
                        save(job); // Covers both staging and its unchanged identity after rename.
                    }
                }
                @Override public void update(String message, long bytes, long total) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("desktop_archive_interrupted");
                    long now = System.currentTimeMillis();
                    if (now - lastUpdate[0] >= 1000) {
                        lastUpdate[0] = now;
                        job.setProperty("progress", android.text.format.Formatter.formatFileSize(context, bytes)
                            + (total > 0 ? " / " + android.text.format.Formatter.formatFileSize(context, total) : ""));
                        DesktopArchiveTasks.this.update(job, job.getProperty("message"), "", progress);
                    }
                }
            };
            if ("export".equals(job.getProperty("operation"))) {
                DesktopDistro distro = new DesktopTasks(context).find(job.getProperty("desktopId"))
                    .orElseThrow(() -> new IOException("desktop_missing"));
                if (distro.getRunMode() != DesktopRunMode.PROOT) throw new IOException("desktop_archive_container_required");
                File container = new File(containers, distro.getProotDistro());
                if (!container.isDirectory()) throw new IOException("desktop_archive_container_missing");
                if (!new File(container, ".desktop-de-" + distro.getDe().getStorageValue()).isFile()) {
                    throw new IOException("desktop_archive_install_marker_missing");
                }
                DesktopSessionOverrides settings = new DesktopSessionOverrides(context);
                OrientationPreference orientation = settings.getOrientation(distro.getId());
                DesktopArchiveMetadata metadata = DesktopArchiveMetadata.create(context.getPackageName(),
                    "aarch64", codename(container), distro, settings.getTouchMode(distro.getId()),
                    orientation == null ? null : orientation.getStorageValue());
                update(job, "desktop_archive_exporting", "", progress);
                job.setProperty("cleanupPending", "true");
                save(job);
                if (!"exclusive-create".equals(job.getProperty("exportMode"))) {
                    throw new IOException("desktop_archive_export_mode_invalid");
                }
                engine.exportArchive(container, archive, metadata, DesktopArchiveExportMode.DIRECT_EXCLUSIVE, control);
                job.remove("cleanupPending");
            } else if ("import".equals(job.getProperty("operation"))) {
                update(job, "desktop_archive_validating", "", progress);
                DesktopArchiveMetadata metadata = engine.readMetadata(archive, context.getPackageName(), "aarch64", control);
                requireSpace(metadata);
                update(job, "desktop_archive_installing_prerequisites", "", progress);
                new DesktopRestorePrerequisites(context).ensure(metadata.getDesktop(),
                    "restore-" + job.getProperty("taskId"), 30 * 60 * 1000L,
                    (id, tail) -> update(job, "desktop_archive_installing_prerequisites", tail, progress));
                requireSpace(metadata);
                if (!containers.isDirectory() && !containers.mkdirs()) {
                    throw new IOException("desktop_archive_container_directory_failed");
                }
                update(job, "desktop_archive_importing", "", progress);
                job.setProperty("cleanupPending", "true");
                save(job);
                imported = engine.importArchive(archive, containers, job.getProperty("newId"),
                    job.getProperty("newAlias"), metadata, control);
                if (!new File(imported.getContainerDirectory(), ".desktop-de-"
                    + imported.getDesktop().getDe().getStorageValue()).isFile()) {
                    throw new IOException("desktop_archive_install_marker_missing");
                }
                // Persist publication phase before creating the inventory record.
                job.setProperty("published", "true");
                save(job);
                new FileDesktopDistroRepository(paths.getDesktopDistrosDirectory()).saveNew(imported.getDesktop(),
                    (temporary, device, inode) -> {
                        job.setProperty("inventoryTemporary", temporary.getName());
                        job.setProperty("inventoryDevice", Long.toString(device));
                        job.setProperty("inventoryInode", Long.toString(inode));
                        job.setProperty("inventoryOwned", "true");
                        save(job); // Identity is durable before the no-replace inventory rename.
                    });
                // Only a successfully reserved new inventory identity may receive overrides.
                DesktopSessionOverrides settings = new DesktopSessionOverrides(context);
                job.setProperty("settingsPublishing", "true");
                save(job);
                settings.restore(imported.getDesktop().getId(), imported.getTouchMode(), imported.getOrientation());
                job.setProperty("desktopId", imported.getDesktop().getId());
                job.remove("cleanupPending");
            } else throw new IOException("desktop_archive_operation_invalid");
            job.setProperty("status", Status.SUCCEEDED.name());
            update(job, "desktop_archive_succeeded", "", progress);
        } catch (Exception error) {
            String details = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            if (imported != null || "true".equals(job.getProperty("cleanupPending"))) {
                job.setProperty("cleanupPending", "true");
                try { save(job); } catch (IOException persistence) { details += "\njournal: " + persistence.getMessage(); }
                try {
                    cleanupInterrupted(job);
                    job.remove("cleanupPending");
                } catch (Exception cleanup) { details += "\ncleanup: " + cleanup.getMessage(); }
            }
            job.setProperty("status", Status.FAILED.name());
            try { update(job, "desktop_archive_failed", details, progress); }
            catch (IOException persistence) { android.util.Log.e("DesktopArchive", "Task state write failed", persistence); }
        } finally {
            synchronized (DesktopOperationGate.LOCK) {
                activeTask = null;
                DesktopOperationGate.releaseArchive();
            }
        }
    }

    private void requireSpace(DesktopArchiveMetadata metadata) throws IOException {
        long needed = metadata.getExpandedBytes() + (256L << 20);
        if (context.getFilesDir().getUsableSpace() < needed) throw new IOException("desktop_archive_space_insufficient");
    }

    private void update(Properties job, String message, String log, Progress progress) throws IOException {
        synchronized (DesktopOperationGate.LOCK) {
            if (!message.equals(job.getProperty("message"))) job.remove("progress");
            job.setProperty("message", message);
            if (!log.isEmpty()) {
                String history = job.getProperty("log", "");
                String combined = history + (history.isEmpty() ? "" : "\n") + log;
                job.setProperty("log", combined.length() > 24576 ? combined.substring(combined.length() - 24576) : combined);
            }
            save(job);
            progress.changed(view(job));
        }
    }

    private void requireDesktopsStopped() throws IOException {
        DesktopTasks desktops = new DesktopTasks(context);
        for (DesktopDistro distro : desktops.list()) {
            DesktopSessionState state = desktops.state(distro);
            if (state == DesktopSessionState.RUNNING || state == DesktopSessionState.STARTING) {
                throw new IOException("desktop_archive_stop_required");
            }
        }
        File[] pids = paths.getDesktopStateDirectory().listFiles((dir, name) ->
            name.endsWith(".pid") || name.endsWith(".pid.container"));
        if (pids != null) for (File pid : pids) {
            try {
                long number = Long.parseLong(com.termux.ui.runtime.DesktopEnvironment.tail(pid, 32).trim());
                if (number > 0 && LocalGames.requireHost(context).isProcessAlive(number)) {
                    throw new IOException("desktop_archive_stop_required");
                }
            } catch (NumberFormatException ignored) { /* Missing or stale PID is not a live process. */ }
        }
        File[] status = paths.getDesktopStateDirectory().listFiles((dir, name) -> name.endsWith(".status"));
        if (status != null) for (File file : status) {
            String value = com.termux.ui.runtime.DesktopEnvironment.tail(file, 64).trim();
            if (value.equals("starting") || value.equals("stopping") || value.equals("installing")
                || value.equals("removing")) throw new IOException("desktop_command_pending");
        }
    }

    private void requireSharedArchive(File archive) throws IOException {
        if (!archive.getName().endsWith(".desktop.tar.gz")
            || !RuntimeBackupSharedStorage.isWithin(Environment.getExternalStorageDirectory(), archive)) {
            throw new IOException("desktop_archive_shared_path_required");
        }
    }

    private String codename(File container) throws IOException {
        File rootfs = new File(container, "rootfs");
        File file = new File(rootfs, "etc/os-release");
        try {
            android.system.StructStat stat = Os.lstat(file.getPath());
            if (OsConstants.S_ISLNK(stat.st_mode)) {
                String link = Os.readlink(file.getPath());
                String full = link.startsWith("/") ? link : "/etc/" + link;
                java.util.ArrayList<String> parts = new java.util.ArrayList<>();
                for (String part : full.split("/")) {
                    if (part.isEmpty() || part.equals(".")) continue;
                    if (part.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
                    else parts.add(part);
                }
                if (!"usr/lib/os-release".equals(android.text.TextUtils.join("/", parts))) {
                    throw new IOException("desktop_archive_os_release_invalid");
                }
                file = new File(rootfs, "usr/lib/os-release");
            }
        } catch (android.system.ErrnoException error) {
            throw new IOException("desktop_archive_os_release_invalid", error);
        }
        File canonical = file.getCanonicalFile();
        if (!RuntimeBackupSharedStorage.isWithin(rootfs, canonical)
            || canonical.length() > 16384) throw new IOException("desktop_archive_os_release_invalid");
        String text = readSmallText(canonical);
        String id = null, code = null;
        for (String line : text.split("\n")) {
            if (line.startsWith("ID=")) id = line.substring(3).replace("\"", "").replace("'", "");
            if (line.startsWith("VERSION_CODENAME=")) code = line.substring(17).replace("\"", "").replace("'", "");
        }
        if (!"debian".equals(id) || code == null) throw new IOException("desktop_archive_distribution_unsupported");
        return code;
    }

    private static String readSmallText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file);
             java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > 16384) throw new IOException("desktop_archive_os_release_invalid");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void cleanupInterrupted(Properties job) throws IOException {
        String alias = job.getProperty("newAlias", "");
        String id = job.getProperty("newId", "");
        if (!alias.matches("import-[0-9a-f-]{36}") || !id.equals("desktop-" + alias)) {
            throw new IOException("desktop_archive_journal_invalid");
        }
        if ("import".equals(job.getProperty("operation"))) {
            IOException failure = null;
            File stage = new File(containers, ".desktop-import-" + alias);
            File target = new File(containers, alias);
            File inventory = new File(paths.getDesktopDistrosDirectory(), id + ".properties");
            try { deleteIfOwned(stage, job, "import", true); }
            catch (IOException error) { failure = error; }
            try {
                if ("true".equals(job.getProperty("inventoryOwned"))) {
                    String temporary = job.getProperty("inventoryTemporary", "");
                    if (!temporary.startsWith(id + ".new-") || temporary.indexOf('/') >= 0)
                        throw new IOException("desktop_archive_journal_invalid");
                    deleteIfOwned(new File(paths.getDesktopDistrosDirectory(), temporary), job, "inventory", false);
                }
                deleteIfOwned(inventory, job, "inventory", false);
            }
            catch (IOException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            // Preserve a container/settings while any unclaimed inventory could still reference it.
            if (failure == null) {
                try { deleteIfOwned(target, job, "import", true); }
                catch (IOException error) { failure = error; }
                if (failure == null && "true".equals(job.getProperty("settingsPublishing"))) {
                    try { new DesktopSessionOverrides(context).restore(id, null, null); }
                    catch (IOException error) { failure = error; }
                }
            }
            if (failure != null) throw failure;
        } else if ("export".equals(job.getProperty("operation"))) {
            File archive = new File(job.getProperty("archive"));
            requireSharedArchive(archive);
            if (!"exclusive-create".equals(job.getProperty("exportMode"))) {
                throw new IOException("desktop_archive_export_mode_invalid");
            }
            if (!"true".equals(job.getProperty("outputOwned"))) {
                // Creation and journalling are separate syscalls. If the process died in that
                // gap, the at-most-empty unclaimed file is preserved; never guess ownership.
                if (archive.exists()) job.setProperty("log", job.getProperty("log", "")
                    + "\nUnclaimed output preserved: " + archive.getPath());
                return;
            }
            try {
                android.system.StructStat stat;
                try { stat = Os.lstat(archive.getPath()); }
                catch (android.system.ErrnoException absent) {
                    if (absent.errno == OsConstants.ENOENT) return;
                    throw absent;
                }
                if (OsConstants.S_ISREG(stat.st_mode)
                    && stat.st_dev == Long.parseLong(job.getProperty("outputDevice"))
                    && stat.st_ino == Long.parseLong(job.getProperty("outputInode"))) {
                    if (!archive.delete()) throw new IOException("desktop_archive_output_cleanup_failed");
                }
            } catch (android.system.ErrnoException | NumberFormatException error) {
                throw new IOException("desktop_archive_output_cleanup_failed", error);
            }
        }
    }

    private static void deleteIfOwned(File file, Properties job, String prefix, boolean directory)
        throws IOException {
        try {
            android.system.StructStat stat;
            try { stat = Os.lstat(file.getPath()); }
            catch (android.system.ErrnoException absent) {
                if (absent.errno == OsConstants.ENOENT) return;
                throw absent;
            }
            if (!"true".equals(job.getProperty(prefix + "Owned"))
                || stat.st_dev != Long.parseLong(job.getProperty(prefix + "Device", "-1"))
                || stat.st_ino != Long.parseLong(job.getProperty(prefix + "Inode", "-1"))
                || (directory ? !OsConstants.S_ISDIR(stat.st_mode) : !OsConstants.S_ISREG(stat.st_mode))) {
                throw new IOException("desktop_archive_unclaimed_path_preserved:" + file.getPath());
            }
            deleteOwned(file);
        } catch (android.system.ErrnoException | NumberFormatException error) {
            throw new IOException("desktop_archive_cleanup_identity_failed:" + file.getPath(), error);
        }
    }

    private static void deleteOwned(File file) throws IOException {
        try {
            android.system.StructStat stat;
            try { stat = Os.lstat(file.getPath()); }
            catch (android.system.ErrnoException absent) {
                if (absent.errno == OsConstants.ENOENT) return;
                throw absent;
            }
            if (OsConstants.S_ISDIR(stat.st_mode)) {
                Os.chmod(file.getPath(), (stat.st_mode & 07777) | 0700);
                File[] children = file.listFiles();
                if (children == null) {
                    Os.chmod(file.getPath(), 0700);
                    children = file.listFiles();
                }
                if (children == null) throw new IOException("desktop_archive_cleanup_unreadable");
                for (File child : children) deleteOwned(child);
            }
            if (!file.delete()) throw new IOException("desktop_archive_cleanup_failed");
        } catch (android.system.ErrnoException error) { throw new IOException(error); }
    }

    private Properties load() throws IOException {
        Properties job = new Properties();
        if (journal.exists()) {
            if (journal.length() > 262144) throw new IOException("desktop_archive_journal_invalid");
            try (FileInputStream input = new FileInputStream(journal)) { job.load(input); }
        }
        return job;
    }
    private void save(Properties job) throws IOException {
        File parent = journal.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("desktop_archive_journal_directory_failed");
        File temporary = new File(parent, journal.getName() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary)) { job.store(output, null); output.getFD().sync(); }
        if (!temporary.renameTo(journal)) throw new IOException("desktop_archive_journal_publish_failed");
    }
    private Snapshot view(Properties job) {
        String log = job.getProperty("log", "");
        if (job.containsKey("progress")) log += (log.isEmpty() ? "" : "\n\n") + job.getProperty("progress");
        return new Snapshot(Status.valueOf(job.getProperty("status")), job.getProperty("taskId", ""),
            job.getProperty("message", ""), log, job.getProperty("desktopId", ""));
    }
}
