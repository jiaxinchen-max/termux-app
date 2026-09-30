package com.termux.localgames.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.termux.localgames.R;
import com.termux.localgames.activity.LocalGamesActivity;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.api.ResetTasks;
import com.termux.localgames.api.RuntimeProvisionRequest;
import com.termux.localgames.data.FileResetTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.ResetTarget;
import com.termux.localgames.domain.ResetTask;
import com.termux.localgames.domain.ResetTaskState;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent owner for purging one runtime environment (GLIBC or RootFS) to not-installed. */
public final class ResetRuntimeForegroundService extends Service {
    private static final String TAG = "GamesRuntimeReset";
    private static final String CHANNEL_ID = "games_runtime_provision";
    private static final int NOTIFICATION_ID = 23099;
    private static final long RESET_TIMEOUT_MS = 30L * 60L * 1000L;
    private static final long RESET_POLL_MS = 250L;

    private final AtomicInteger pendingCommands = new AtomicInteger();
    private ExecutorService executor;
    private GameStoragePaths paths;
    private FileResetTaskRepository tasks;
    private LocalGamesHost host;

    @Override
    public void onCreate() {
        super.onCreate();
        paths = new GameStoragePaths(getFilesDir());
        tasks = new FileResetTaskRepository(paths.getResetTasksDirectory());
        host = LocalGames.requireHost(this);
        executor = Executors.newSingleThreadExecutor(runnable ->
            new Thread(runnable, "GamesRuntimeReset"));
        createChannel();
        startForeground(NOTIFICATION_ID, notification(null));
        pendingCommands.incrementAndGet();
        executor.execute(() -> {
            try {
                reconcileAll();
            } finally {
                if (pendingCommands.decrementAndGet() == 0) {
                    stopForeground(false);
                    stopSelf();
                }
            }
        });
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_NOT_STICKY;
        String action = intent.getAction();
        String taskId = intent.getStringExtra(ResetTasks.EXTRA_TASK_ID);
        String targetName = intent.getStringExtra(ResetTasks.EXTRA_TARGET);
        String resetKey = intent.getStringExtra(ResetTasks.EXTRA_RESET_KEY);
        pendingCommands.incrementAndGet();
        executor.execute(() -> {
            try {
                if (ResetTasks.ACTION_ENQUEUE.equals(action)) {
                    enqueue(requiredId(taskId), ResetTarget.valueOf(targetName), resetKey);
                } else if (ResetTasks.ACTION_RECONCILE_ALL.equals(action)) {
                    reconcileAll();
                }
            } catch (Exception error) {
                Log.e(TAG, "Reset command failed", error);
                fail(taskId, stableError(error));
            } finally {
                if (pendingCommands.decrementAndGet() == 0) {
                    stopForeground(false);
                    stopSelf();
                }
            }
        });
        return START_NOT_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private void enqueue(String taskId, ResetTarget target, String resetKey) throws Exception {
        if (tasks.find(taskId).isPresent()) return;
        RuntimeInstallationGate.requireResetSlot(getFilesDir(), taskId);
        ResetTask task = ResetTask.queued(taskId, target, resetKey, System.currentTimeMillis());
        tasks.save(task);
        transition(task, ResetTaskState.RUNNING, "");
        publish(task);
        if (target == ResetTarget.GLIBC) runGlibcReset(taskId);
        else runRootfsReset(taskId, resetKey);
        transition(requiredTask(taskId), ResetTaskState.SUCCEEDED, "");
    }

    private void runGlibcReset(String taskId) throws Exception {
        File script = new LaunchScriptInstaller(this, paths).installResetTermuxGlibcRuntime();
        File spec = new File(paths.getResetSpecsDirectory(), taskId + ".conf");
        File log = new File(paths.getResetLogsDirectory(), taskId + ".log");
        File event = new File(paths.getResetEventsDirectory(), taskId + ".event");
        if (event.exists() && !event.delete()) throw new IOException("reset_event_cleanup_failed");
        writeShellSpec(spec, new String[][]{
            {"TERMUX_GLIBC_TASK_ID", taskId},
            {"TERMUX_GLIBC_LOG_FILE", log.getCanonicalPath()},
            {"TERMUX_GLIBC_EVENT_FILE", event.getCanonicalPath()},
        });
        host.startTermuxPackageInstall(new RuntimeProvisionRequest(taskId,
            script.getCanonicalPath(), spec.getCanonicalPath(),
            paths.getRuntimeDirectory().getCanonicalPath()));
        String state = waitForEvent(event);
        if (!"SUCCEEDED".equals(state)) throw new IOException("glibc_reset_failed");
    }

    /** $2 is the current recipeSha256 (64 lowercase hex chars), not a game containerId --
     *  resetting RootFS only tears down the shared rootfs template that new containers are
     *  cloned from (see provision_rootfs_runtime.sh's TEMPLATE_CONTAINER_NAME, which this
     *  must stay in sync with); already-cloned game containers are untouched and keep working. */
    private void runRootfsReset(String taskId, String recipeSha256) throws Exception {
        String requiredRecipeSha256 = requireRecipeSha256(recipeSha256);
        String templateContainerName = "tmpl-" + requiredRecipeSha256.substring(0, 16);
        File script = new LaunchScriptInstaller(this, paths).installResetRootfsRuntime();
        File spec = new File(paths.getResetSpecsDirectory(), taskId + ".conf");
        File log = new File(paths.getResetLogsDirectory(), taskId + ".log");
        File event = new File(paths.getResetEventsDirectory(), taskId + ".event");
        if (event.exists() && !event.delete()) throw new IOException("reset_event_cleanup_failed");
        File templateDirectory = new File(paths.getProotDistroContainersDirectory(),
            templateContainerName);
        writeShellSpec(spec, new String[][]{
            {"RESET_LOG_FILE", log.getCanonicalPath()},
            {"RESET_EVENT_FILE", event.getCanonicalPath()},
            {"RESET_CONTAINER_ID", templateContainerName},
            {"RESET_CONTAINER_DIR", templateDirectory.getCanonicalPath()},
            {"RESET_METADATA_DIR", ""},
        });
        host.startRuntimeProvision(new RuntimeProvisionRequest(taskId,
            script.getCanonicalPath(), spec.getCanonicalPath(),
            paths.getResetDirectory().getCanonicalPath()));
        String state = waitForEvent(event);
        if (!"SUCCEEDED".equals(state)) throw new IOException("rootfs_reset_failed");
    }

    private void reconcileAll() {
        try {
            List<ResetTask> snapshots = tasks.list();
            for (ResetTask task : snapshots) {
                if (task.getState().isTerminal()) continue;
                // A non-terminal task found at service start means the process died mid-reset
                // (the actual work always runs synchronously within one executor submission).
                fail(task.getTaskId(), "reset_interrupted");
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to list reset tasks", error);
        }
    }

    private static String waitForEvent(File event) throws IOException {
        long deadline = System.currentTimeMillis() + RESET_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            String state = readFirstLine(event);
            if ("SUCCEEDED".equals(state) || "FAILED".equals(state)) return state;
            try {
                Thread.sleep(RESET_POLL_MS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IOException("reset_interrupted", error);
            }
        }
        throw new IOException("reset_timeout");
    }

    private static void writeShellSpec(File file, String[][] entries) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("reset_spec_directory_failed");
        try (Writer output = new OutputStreamWriter(new FileOutputStream(file, false),
            StandardCharsets.UTF_8)) {
            for (String[] entry : entries) {
                output.write(entry[0] + "=" + shellValue(entry[1]) + "\n");
            }
        }
    }

    private static String readFirstLine(File file) throws IOException {
        if (!file.isFile()) return "";
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        }
    }

    private static String shellValue(String value) {
        return "'" + value.replace("'", "'\\\"'\\\"'") + "'";
    }

    private ResetTask transition(ResetTask task, ResetTaskState state, String error)
        throws IOException {
        ResetTask updated = task.transition(state, error, System.currentTimeMillis());
        tasks.save(updated);
        publish(updated);
        return updated;
    }

    private void fail(String taskId, String code) {
        if (taskId == null) return;
        try {
            ResetTask task = tasks.find(taskId).orElse(null);
            if (task != null && !task.getState().isTerminal()) {
                transition(task, ResetTaskState.FAILED, code);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to persist reset failure", error);
        }
    }

    private ResetTask requiredTask(String taskId) throws IOException {
        return tasks.find(taskId).orElseThrow(() -> new IOException("reset_task_missing"));
    }

    private void publish(ResetTask task) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(task));
    }

    private Notification notification(@Nullable ResetTask task) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent target = new Intent(this, LocalGamesActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        String text = task == null ? getString(R.string.local_games_reset_in_progress) :
            getString(R.string.local_games_reset_status, task.getTarget().name(),
                task.getState().name());
        return builder.setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.local_games_reset_title))
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, target, flags))
            .setOnlyAlertOnce(true)
            .setOngoing(task == null || !task.getState().isTerminal())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(0, 0, task == null || !task.getState().isTerminal())
            .build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            getString(R.string.local_games_runtime_provision_channel),
            NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }

    private static String requiredId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("invalid_reset_identifier");
        }
        return value;
    }

    private static String requireRecipeSha256(String value) throws IOException {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IOException("invalid_recipe_sha256");
        }
        return value;
    }

    private static String stableError(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z0-9_:.+-]{1,128}")
            ? message : "runtime_reset_failed";
    }
}
