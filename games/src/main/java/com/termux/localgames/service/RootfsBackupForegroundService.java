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
import com.termux.localgames.api.RootfsBackupTasks;
import com.termux.localgames.api.RuntimeSetupRequest;
import com.termux.localgames.data.FileRootfsBackupTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.RootfsBackupSpecCodec;
import com.termux.localgames.domain.RootfsBackupTask;
import com.termux.localgames.domain.RootfsBackupTaskState;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONException;
import org.json.JSONObject;

/** Persistent owner for the base-environment Backup/Restore feature -- mirrors
 *  RootfsSetupForegroundService's reconcile-loop shape, but with a much simpler enqueue: no
 *  recipe/component resolution, no per-container prefix, just "run backup_restore_rootfs.sh
 *  against the one external backup file and the one shared RootFS". */
public final class RootfsBackupForegroundService extends Service {
    private static final String TAG = "GamesRootfsBackup";
    private static final String CHANNEL_ID = "games_runtime_backup";
    private static final int NOTIFICATION_ID = 23094;

    private final Set<String> submitted = new HashSet<>();
    private final AtomicInteger pendingCommands = new AtomicInteger();
    private volatile boolean receivedStart;
    private ScheduledExecutorService executor;
    private GameStoragePaths paths;
    private FileRootfsBackupTaskRepository tasks;
    private LocalGamesHost host;

    @Override
    public void onCreate() {
        super.onCreate();
        paths = new GameStoragePaths(getFilesDir());
        tasks = new FileRootfsBackupTaskRepository(paths.getBackupTasksDirectory());
        host = LocalGames.requireHost(this);
        executor = Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "GamesRootfsBackup"));
        createChannel();
        startForeground(NOTIFICATION_ID, notification(null));
        executor.scheduleWithFixedDelay(this::reconcileAll, 500, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        receivedStart = true;
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();
        String taskId = intent.getStringExtra(RootfsBackupTasks.EXTRA_TASK_ID);
        String kindName = intent.getStringExtra(RootfsBackupTasks.EXTRA_KIND);
        pendingCommands.incrementAndGet();
        executor.execute(() -> {
            try {
                if (RootfsBackupTasks.ACTION_ENQUEUE.equals(action)) {
                    enqueue(requiredId(taskId), requiredKind(kindName));
                } else if (RootfsBackupTasks.ACTION_RECONCILE.equals(action)) {
                    reconcile(requiredTask(requiredId(taskId)));
                } else if (RootfsBackupTasks.ACTION_RECONCILE_ALL.equals(action)) {
                    reconcileAll();
                }
            } catch (Exception error) {
                Log.e(TAG, "Backup command failed", error);
                fail(taskId, stableError(error));
            } finally {
                pendingCommands.decrementAndGet();
            }
        });
        return START_STICKY;
    }

    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private void enqueue(String taskId, RootfsBackupTask.Kind kind) throws Exception {
        if (tasks.find(taskId).isPresent()) return;
        RootfsBackupTask task = RootfsBackupTask.queued(taskId, kind, System.currentTimeMillis());
        tasks.save(task);
        prepareAndStart(task);
    }

    private void prepareAndStart(RootfsBackupTask task) throws Exception {
        if (!submitted.add(task.getTaskId())) return;
        try {
            File script = new LaunchScriptInstaller(this, paths).installBackupRestoreRootfs();
            File spec = new File(paths.getBackupSpecsDirectory(), task.getTaskId() + ".backupspec");
            File events = eventFile(task.getTaskId());
            File log = new File(paths.getBackupLogsDirectory(), task.getTaskId() + ".log");
            new RootfsBackupSpecCodec().write(spec, task, paths.getExternalBackupFile(),
                events, log);
            host.startRuntimeSetup(new RuntimeSetupRequest(task.getTaskId(),
                script.getCanonicalPath(), spec.getCanonicalPath(),
                paths.getBackupDirectory().getCanonicalPath()));
            transition(task, RootfsBackupTaskState.RUNNING, "");
        } catch (Exception error) {
            submitted.remove(task.getTaskId());
            throw error;
        }
    }

    private void reconcileAll() {
        try {
            List<RootfsBackupTask> snapshots = tasks.list();
            boolean active = false;
            for (RootfsBackupTask task : snapshots) {
                if (task.getState().isTerminal()) continue;
                active = true;
                try { reconcile(task); }
                catch (Exception error) {
                    Log.e(TAG, "Backup reconciliation failed", error);
                    fail(task.getTaskId(), stableError(error));
                }
            }
            if (receivedStart && pendingCommands.get() == 0 && !active) {
                stopForeground(false);
                stopSelf();
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to list backup tasks", error);
        }
    }

    private void reconcile(RootfsBackupTask task) throws Exception {
        BackupEvent event = readLastEvent(eventFile(task.getTaskId()));
        if (event.state == Event.SUCCEEDED) {
            RootfsBackupTask current = requiredTask(task.getTaskId());
            if (!current.getState().isTerminal()) {
                transition(current, RootfsBackupTaskState.SUCCEEDED, "");
            }
            submitted.remove(task.getTaskId());
        } else if (event.state == Event.FAILED) {
            fail(task.getTaskId(), event.errorCode);
            submitted.remove(task.getTaskId());
        } else if (!submitted.contains(task.getTaskId())) {
            prepareAndStart(task);
        }
    }

    private RootfsBackupTask transition(RootfsBackupTask task, RootfsBackupTaskState state,
                                        String error) throws IOException {
        RootfsBackupTask updated = task.transition(state, error, System.currentTimeMillis());
        tasks.save(updated);
        publish(updated);
        return updated;
    }

    private void fail(String taskId, String code) {
        if (taskId == null) return;
        try {
            RootfsBackupTask task = tasks.find(taskId).orElse(null);
            if (task != null && !task.getState().isTerminal()) {
                transition(task, RootfsBackupTaskState.FAILED, code);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to persist backup failure", error);
        }
    }

    private RootfsBackupTask requiredTask(String taskId) throws IOException {
        return tasks.find(taskId).orElseThrow(() -> new IOException("backup_task_missing"));
    }

    private File eventFile(String taskId) {
        return new File(paths.getBackupEventsDirectory(), taskId + ".jsonl");
    }

    private static BackupEvent readLastEvent(File file) throws IOException {
        if (!file.isFile()) return BackupEvent.none();
        String last = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 4096) throw new IOException("backup_event_too_large");
                if (!line.trim().isEmpty()) last = line;
            }
        }
        if (last == null) return BackupEvent.none();
        try {
            JSONObject value = new JSONObject(last);
            String state = value.optString("state", "RUNNING");
            if ("SUCCEEDED".equals(state)) {
                return new BackupEvent(Event.SUCCEEDED, "");
            }
            if ("FAILED".equals(state)) {
                String code = value.optString("errorCode", "backup_script_failed");
                if (!code.matches("[a-z0-9_:.+-]{1,128}")) code = "backup_script_failed";
                return new BackupEvent(Event.FAILED, code);
            }
            return new BackupEvent(Event.RUNNING, "");
        } catch (JSONException error) {
            throw new IOException("backup_event_invalid", error);
        }
    }

    private void publish(RootfsBackupTask task) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(task));
    }

    private Notification notification(@Nullable RootfsBackupTask task) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent target = new Intent(this, LocalGamesActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        String text = task == null ? getString(R.string.local_games_runtime_setup_preparing) :
            getString(R.string.local_games_runtime_setup_status,
                task.getKind().name(), task.getState().name());
        return builder.setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.local_games_backup_title))
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
            getString(R.string.local_games_backup_channel),
            NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }

    private static String requiredId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("invalid_backup_identifier");
        }
        return value;
    }

    private static RootfsBackupTask.Kind requiredKind(String value) throws IOException {
        try {
            return RootfsBackupTask.Kind.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("invalid_backup_kind");
        }
    }

    private static String stableError(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z0-9_:.+-]{1,128}")
            ? message : "backup_task_failed";
    }

    private enum Event { NONE, RUNNING, SUCCEEDED, FAILED }

    private static final class BackupEvent {
        final Event state;
        final String errorCode;

        BackupEvent(Event state, String errorCode) {
            this.state = state;
            this.errorCode = errorCode;
        }

        static BackupEvent none() {
            return new BackupEvent(Event.NONE, "");
        }
    }
}
