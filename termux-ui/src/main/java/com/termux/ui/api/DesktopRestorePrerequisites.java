package com.termux.ui.api;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;

import com.termux.terminal.TerminalSession;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;
import com.termux.ui.runtime.DesktopEnvironment;
import com.termux.ui.runtime.DesktopLaunchSpec;
import com.termux.ui.service.LaunchScriptInstaller;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/** Blocking host-only package preflight, completed before a container archive is published. */
public final class DesktopRestorePrerequisites {
    public interface ProgressListener {
        void onProgress(String taskId, String logTail) throws IOException;
    }

    public static final class Result {
        private final String taskId;
        private final File logFile;
        private final File resultFile;

        private Result(String taskId, File logFile, File resultFile) {
            this.taskId = taskId;
            this.logFile = logFile;
            this.resultFile = resultFile;
        }

        public String getTaskId() { return taskId; }
        public File getLogFile() { return logFile; }
        public File getResultFile() { return resultFile; }
    }

    private final Context context;
    private final GameStoragePaths paths;

    public DesktopRestorePrerequisites(Context context) {
        this.context = context.getApplicationContext();
        paths = new GameStoragePaths(this.context.getFilesDir());
    }

    /** Prevents a timed-out install from overlapping a later archive operation. */
    public static void requireIdle(Context context) throws IOException {
        File parent = new File(new GameStoragePaths(context.getApplicationContext().getFilesDir())
            .getDesktopDirectory(), "restore-prerequisites");
        File[] directories = parent.listFiles();
        if (directories == null) {
            if (parent.exists()) throw new IOException("desktop_prerequisites_tasks_unreadable");
            return;
        }
        LocalGamesHost host = LocalGames.requireHost(context);
        long now = System.currentTimeMillis();
        for (File directory : directories) {
            if (!directory.isDirectory()) continue;
            String id = directory.getName();
            TerminalSession terminal = host.getRuntimeSetupTerminal(id);
            if (terminal != null && terminal.isRunning()) {
                throw new IOException("desktop_prerequisites_still_running:" + id);
            }
            File request = new File(directory, "request.properties");
            if (new File(directory, "result.properties").exists() || terminal != null) continue;
            long submitted = directory.lastModified();
            if (request.isFile()) {
                Properties properties = new Properties();
                try (FileInputStream input = new FileInputStream(request)) {
                    properties.load(input);
                    if (!id.equals(properties.getProperty("taskId"))) {
                        throw new IOException("desktop_prerequisites_task_invalid:" + id);
                    }
                    submitted = Long.parseLong(properties.getProperty("submittedAtMillis"));
                } catch (IllegalArgumentException error) {
                    throw new IOException("desktop_prerequisites_task_invalid:" + id, error);
                }
            }
            if (now - submitted < 30000) {
                throw new IOException("desktop_prerequisites_dispatch_pending:" + id);
            }
            // No result is never a successful preflight. After dispatch expires, a fresh
            // task may retry, but ensure() still rejects this incomplete task's result.
        }
    }

    /** Must run on a service worker. A timeout fails the restore; it never kills dpkg mid-write. */
    public Result ensure(DesktopDistro distro, String taskId, long timeoutMillis,
                         ProgressListener listener)
        throws IOException, LaunchHostException, InterruptedException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("desktop_prerequisites_worker_required");
        }
        if (distro == null || distro.getRunMode() != DesktopRunMode.PROOT
            || !distro.getDe().isCreatable() || distro.getGlMode() == DesktopGlMode.AUTO) {
            throw new IllegalArgumentException("desktop_prerequisites_explicit_proot_mode_required");
        }
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}")
            || taskId.equals(".") || taskId.equals("..") || timeoutMillis <= 0) {
            throw new IllegalArgumentException("desktop_prerequisites_invalid_task");
        }
        requireIdle(context);
        File parent = new File(paths.getDesktopDirectory(), "restore-prerequisites");
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("desktop_prerequisites_directory_failed");
        }
        File directory = new File(parent, taskId);
        if (!directory.mkdir()) throw new IOException("desktop_prerequisites_task_already_exists");
        File spec = new File(directory, "spec");
        File log = new File(directory, "install.log");
        File result = new File(directory, "result.properties");
        File wrapper = new File(directory, "host-prerequisites.sh");
        LaunchScriptInstaller.DesktopScripts scripts =
            new LaunchScriptInstaller(context, paths).installDesktopScripts();
        write(spec, DesktopLaunchSpec.encode(distro, new File(directory, "pid").getPath(),
            new File(directory, "status").getPath(), log.getPath()), false);
        write(wrapper, "#!/data/data/com.termux/files/usr/bin/sh\nexec "
            + quote(scripts.rawInstall.getPath()) + " \"$1\" --operation=host-prerequisites "
            + "--task-id=" + quote(taskId) + " --result-file=" + quote(result.getPath()) + "\n", true);
        LocalGamesHost host = LocalGames.requireHost(context);
        write(new File(directory, "request.properties"), "taskId=" + taskId
            + "\nsubmittedAtMillis=" + System.currentTimeMillis() + "\n", false);
        host.startTermuxPackageInstall(new RuntimeSetupRequest(taskId, wrapper.getPath(),
            spec.getPath(), directory.getPath()));
        long started = SystemClock.elapsedRealtime();
        TerminalSession terminal = null;
        String previousLog = null;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (terminal == null) terminal = host.getRuntimeSetupTerminal(taskId);
            String tail = DesktopEnvironment.tail(log, 16384);
            if (listener != null && !tail.equals(previousLog)) {
                listener.onProgress(taskId, tail);
                previousLog = tail;
            }
            if (terminal != null && !terminal.isRunning() && terminal.getExitStatus() != 0) {
                throw new IOException("desktop_prerequisites_terminal_exit:" + terminal.getExitStatus()
                    + ":" + log.getPath());
            }
            if (result.exists()) {
                validateResult(result, taskId);
                // The atomic EXIT result also covers a task that finishes before its terminal
                // registry entry can be observed. A captured terminal must independently exit 0.
                if (terminal == null || !terminal.isRunning()) return new Result(taskId, log, result);
            } else if (terminal != null && !terminal.isRunning()) {
                throw new IOException("desktop_prerequisites_result_missing:" + log.getPath());
            }
            if (SystemClock.elapsedRealtime() - started >= timeoutMillis) {
                throw new IOException("desktop_prerequisites_timeout:" + taskId + ":" + log.getPath());
            }
            Thread.sleep(200);
        }
    }

    private static void validateResult(File result, String taskId) throws IOException {
        if (!result.isFile() || result.length() > 4096) {
            throw new IOException("desktop_prerequisites_result_invalid");
        }
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(result)) {
            properties.load(input);
        } catch (IllegalArgumentException error) {
            throw new IOException("desktop_prerequisites_result_invalid", error);
        }
        if (properties.size() != 5 || !"1".equals(properties.getProperty("schemaVersion"))
            || !taskId.equals(properties.getProperty("taskId"))
            || !"host-prerequisites".equals(properties.getProperty("operation"))) {
            throw new IOException("desktop_prerequisites_result_invalid");
        }
        if (!"succeeded".equals(properties.getProperty("state"))
            || !"0".equals(properties.getProperty("exitCode"))) {
            throw new IOException("desktop_prerequisites_failed:"
                + properties.getProperty("exitCode") + ":" + result.getPath());
        }
    }

    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    private static void write(File target, String value, boolean executable) throws IOException {
        File temporary = File.createTempFile(target.getName(), ".tmp", target.getParentFile());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(value.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            try { Os.chmod(temporary.getPath(), executable ? 0700 : 0600); }
            catch (ErrnoException error) { throw new IOException("desktop_prerequisites_chmod_failed", error); }
            if (!temporary.renameTo(target)) throw new IOException("desktop_prerequisites_publish_failed");
        } finally {
            temporary.delete();
        }
    }
}
