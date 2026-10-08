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
import com.termux.localgames.api.CustomComponentInstallTasks;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.api.RuntimeSetupRequest;
import com.termux.localgames.data.CustomComponentInstallSpecCodec;
import com.termux.localgames.data.FileCustomComponentInstallTaskRepository;
import com.termux.localgames.data.FileCustomRuntimeComponentRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.CustomComponentInstallTask;
import com.termux.localgames.domain.CustomComponentInstallTaskState;
import com.termux.localgames.domain.CustomRuntimeComponent;
import com.termux.localgames.domain.RuntimeTranslator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONException;
import org.json.JSONObject;

/** Persistent owner for installing a user-picked local Wine/Box64 build into the shared RootFS --
 *  mirrors RootfsBackupForegroundService's reconcile-loop shape. Unlike that service, SUCCEEDED
 *  also writes a new CustomRuntimeComponent registry entry (see
 *  FileCustomRuntimeComponentRepository), reconstructed from fields carried on the task record
 *  itself so this is self-healing across a process restart -- see CustomComponentInstallTask's
 *  own doc comment. */
public final class CustomComponentInstallForegroundService extends Service {
    private static final String TAG = "GamesCustomInstall";
    private static final String CHANNEL_ID = "games_custom_component_install";
    private static final int NOTIFICATION_ID = 23095;

    private final Set<String> submitted = new HashSet<>();
    private final AtomicInteger pendingCommands = new AtomicInteger();
    private volatile boolean receivedStart;
    private ScheduledExecutorService executor;
    private GameStoragePaths paths;
    private FileCustomComponentInstallTaskRepository tasks;
    private LocalGamesHost host;

    @Override
    public void onCreate() {
        super.onCreate();
        paths = new GameStoragePaths(getFilesDir());
        tasks = new FileCustomComponentInstallTaskRepository(
            paths.getCustomComponentInstallTasksDirectory());
        host = LocalGames.requireHost(this);
        executor = Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "GamesCustomInstall"));
        createChannel();
        startForeground(NOTIFICATION_ID, notification(null));
        executor.scheduleWithFixedDelay(this::reconcileAll, 500, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        receivedStart = true;
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();
        String taskId = intent.getStringExtra(CustomComponentInstallTasks.EXTRA_TASK_ID);
        pendingCommands.incrementAndGet();
        executor.execute(() -> {
            try {
                if (CustomComponentInstallTasks.ACTION_ENQUEUE.equals(action)) {
                    enqueue(requiredId(taskId),
                        requiredId(intent.getStringExtra(CustomComponentInstallTasks.EXTRA_COMPONENT_ID)),
                        requiredKind(intent.getStringExtra(CustomComponentInstallTasks.EXTRA_KIND)),
                        optionalTranslator(intent.getStringExtra(CustomComponentInstallTasks.EXTRA_TRANSLATOR)),
                        requiredText(intent.getStringExtra(CustomComponentInstallTasks.EXTRA_DISPLAY_NAME)),
                        requiredText(intent.getStringExtra(CustomComponentInstallTasks.EXTRA_PAYLOAD_PATH)),
                        requiredText(intent.getStringExtra(CustomComponentInstallTasks.EXTRA_SHA256)));
                } else if (CustomComponentInstallTasks.ACTION_RECONCILE.equals(action)) {
                    reconcile(requiredTask(requiredId(taskId)));
                } else if (CustomComponentInstallTasks.ACTION_RECONCILE_ALL.equals(action)) {
                    reconcileAll();
                }
            } catch (Exception error) {
                Log.e(TAG, "Custom component install command failed", error);
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

    private void enqueue(String taskId, String componentId, CustomRuntimeComponent.Kind kind,
            RuntimeTranslator translator, String displayName, String payloadPath, String sha256)
            throws Exception {
        if (tasks.find(taskId).isPresent()) return;
        // The UI stages the SAF-drained file at a path of its own choosing (it does not know
        // taskId yet, since that is minted inside CustomComponentInstallTasks.enqueue()) -- move
        // it into the canonical taskId-named staging location now, so stagedPayloadFile(taskId)
        // is a reliable invariant for every caller from this point on, including the
        // restart-recovery path in reconcile().
        File staged = stagedPayloadFile(taskId);
        File source = new File(payloadPath);
        if (!source.getCanonicalFile().equals(staged.getCanonicalFile())) {
            File parent = staged.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("custom_install_staging_directory_failed");
            }
            if (!source.renameTo(staged)) throw new IOException("custom_install_staging_move_failed");
        }
        CustomComponentInstallTask task = CustomComponentInstallTask.queued(taskId, componentId,
            kind, translator, displayName, sha256, System.currentTimeMillis());
        tasks.save(task);
        prepareAndStart(task, staged);
    }

    private void prepareAndStart(CustomComponentInstallTask task, File payload) throws Exception {
        if (!submitted.add(task.getTaskId())) return;
        try {
            File script = new LaunchScriptInstaller(this, paths).installCustomComponentScript();
            File spec = new File(paths.getCustomComponentInstallSpecsDirectory(),
                task.getTaskId() + ".installspec");
            File events = eventFile(task.getTaskId());
            File log = new File(paths.getCustomComponentInstallLogsDirectory(),
                task.getTaskId() + ".log");
            String payloadKind = classifyPayloadKind(task.getKind(), payload);
            new CustomComponentInstallSpecCodec().write(spec, task, payload, payloadKind,
                events, log);
            host.startRuntimeSetup(new RuntimeSetupRequest(task.getTaskId(),
                script.getCanonicalPath(), spec.getCanonicalPath(),
                paths.getCustomComponentInstallDirectory().getCanonicalPath()));
            transition(task, CustomComponentInstallTaskState.RUNNING, "");
        } catch (Exception error) {
            submitted.remove(task.getTaskId());
            throw error;
        }
    }

    /** WINE payloads are always a wine-tree archive (the only shape install_custom_rootfs_
     *  component.sh supports for that kind). BOX64 payloads are classified by magic bytes: an ar
     *  archive header ("!<arch>\n") is a .deb, an ELF header is a raw binary -- anything else is
     *  rejected before ever enqueuing the shell task. */
    private static String classifyPayloadKind(CustomRuntimeComponent.Kind kind, File payload)
            throws IOException {
        if (kind == CustomRuntimeComponent.Kind.WINE) return "wine-tree";
        byte[] header = new byte[8];
        int read;
        try (InputStream input = new FileInputStream(payload)) {
            read = input.read(header);
        }
        if (read >= 7 && header[0] == '!' && header[1] == '<' && header[2] == 'a' &&
            header[3] == 'r' && header[4] == 'c' && header[5] == 'h' && header[6] == '>') {
            return "deb";
        }
        if (read >= 4 && header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' &&
            header[3] == 'F') {
            return "raw-binary";
        }
        throw new IOException("custom_box64_payload_format_unrecognized");
    }

    private void reconcileAll() {
        try {
            List<CustomComponentInstallTask> snapshots = tasks.list();
            boolean active = false;
            for (CustomComponentInstallTask task : snapshots) {
                if (task.getState().isTerminal()) continue;
                active = true;
                try { reconcile(task); }
                catch (Exception error) {
                    Log.e(TAG, "Custom component install reconciliation failed", error);
                    fail(task.getTaskId(), stableError(error));
                }
            }
            if (receivedStart && pendingCommands.get() == 0 && !active) {
                stopForeground(false);
                stopSelf();
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to list custom component install tasks", error);
        }
    }

    private void reconcile(CustomComponentInstallTask task) throws Exception {
        InstallEvent event = readLastEvent(eventFile(task.getTaskId()));
        if (event.state == Event.SUCCEEDED) {
            CustomComponentInstallTask current = requiredTask(task.getTaskId());
            if (!current.getState().isTerminal()) {
                registerComponent(current);
                transition(current, CustomComponentInstallTaskState.SUCCEEDED, "");
            }
            submitted.remove(task.getTaskId());
            cleanupStaging(task.getTaskId());
        } else if (event.state == Event.FAILED) {
            fail(task.getTaskId(), event.errorCode);
            submitted.remove(task.getTaskId());
            cleanupStaging(task.getTaskId());
        } else if (!submitted.contains(task.getTaskId())) {
            // Same "not yet locally submitted" re-arm RootfsBackupForegroundService's reconcile()
            // uses -- covers a process restart mid-install. Re-derive the staged payload path the
            // same way enqueue() originally would have, from the fixed per-task staging convention
            // (host.startRuntimeSetup is itself idempotent against an already-running session with
            // the same taskId-derived shell name, so re-calling prepareAndStart here is safe even
            // if the shell script is still actively running).
            prepareAndStart(task, stagedPayloadFile(task.getTaskId()));
        }
    }

    private void registerComponent(CustomComponentInstallTask task) throws IOException {
        CustomRuntimeComponent entry = new CustomRuntimeComponent(task.getComponentId(),
            task.getKind(), task.getTranslator(), task.getDisplayName(),
            System.currentTimeMillis() / 1000, task.getSha256());
        new FileCustomRuntimeComponentRepository(paths.getCustomRuntimeComponentsDirectory())
            .save(entry);
    }

    private void cleanupStaging(String taskId) {
        File staged = stagedPayloadFile(taskId);
        if (staged.isFile() && !staged.delete()) {
            Log.w(TAG, "Unable to delete staged custom component payload: " + staged);
        }
    }

    private File stagedPayloadFile(String taskId) {
        return new File(paths.getCustomComponentStagingDirectory(), taskId + ".payload");
    }

    private CustomComponentInstallTask transition(CustomComponentInstallTask task,
            CustomComponentInstallTaskState state, String error) throws IOException {
        CustomComponentInstallTask updated = task.transition(state, error,
            System.currentTimeMillis());
        tasks.save(updated);
        publish(updated);
        return updated;
    }

    private void fail(String taskId, String code) {
        if (taskId == null) return;
        try {
            CustomComponentInstallTask task = tasks.find(taskId).orElse(null);
            if (task != null && !task.getState().isTerminal()) {
                transition(task, CustomComponentInstallTaskState.FAILED, code);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to persist custom component install failure", error);
        }
        cleanupStaging(taskId);
    }

    private CustomComponentInstallTask requiredTask(String taskId) throws IOException {
        return tasks.find(taskId).orElseThrow(() -> new IOException("custom_install_task_missing"));
    }

    private File eventFile(String taskId) {
        return new File(paths.getCustomComponentInstallEventsDirectory(), taskId + ".jsonl");
    }

    private static InstallEvent readLastEvent(File file) throws IOException {
        if (!file.isFile()) return InstallEvent.none();
        String last = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 4096) throw new IOException("custom_install_event_too_large");
                if (!line.trim().isEmpty()) last = line;
            }
        }
        if (last == null) return InstallEvent.none();
        try {
            JSONObject value = new JSONObject(last);
            String state = value.optString("state", "RUNNING");
            if ("SUCCEEDED".equals(state)) {
                return new InstallEvent(Event.SUCCEEDED, "");
            }
            if ("FAILED".equals(state)) {
                String code = value.optString("errorCode", "custom_install_script_failed");
                if (!code.matches("[a-z0-9_:.+-]{1,128}")) code = "custom_install_script_failed";
                return new InstallEvent(Event.FAILED, code);
            }
            return new InstallEvent(Event.RUNNING, "");
        } catch (JSONException error) {
            throw new IOException("custom_install_event_invalid", error);
        }
    }

    private void publish(CustomComponentInstallTask task) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(task));
    }

    private Notification notification(@Nullable CustomComponentInstallTask task) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent target = new Intent(this, LocalGamesActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        String text = task == null ? getString(R.string.local_games_runtime_setup_preparing) :
            getString(R.string.local_games_runtime_setup_status,
                task.getComponentId(), task.getState().name());
        return builder.setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.local_games_custom_component_install_title))
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
            getString(R.string.local_games_custom_component_channel),
            NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }

    private static String requiredId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("invalid_custom_install_identifier");
        }
        return value;
    }

    private static String requiredText(String value) throws IOException {
        if (value == null || value.isEmpty()) throw new IOException("invalid_custom_install_field");
        return value;
    }

    private static CustomRuntimeComponent.Kind requiredKind(String value) throws IOException {
        try {
            return CustomRuntimeComponent.Kind.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException error) {
            throw new IOException("invalid_custom_install_kind");
        }
    }

    private static RuntimeTranslator optionalTranslator(String value) throws IOException {
        if (value == null || value.isEmpty()) return null;
        try {
            return RuntimeTranslator.fromStorageValue(value);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_custom_install_translator");
        }
    }

    private static String stableError(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z0-9_:.+-]{1,128}")
            ? message : "custom_install_task_failed";
    }

    private enum Event { NONE, RUNNING, SUCCEEDED, FAILED }

    private static final class InstallEvent {
        final Event state;
        final String errorCode;

        InstallEvent(Event state, String errorCode) {
            this.state = state;
            this.errorCode = errorCode;
        }

        static InstallEvent none() {
            return new InstallEvent(Event.NONE, "");
        }
    }
}
