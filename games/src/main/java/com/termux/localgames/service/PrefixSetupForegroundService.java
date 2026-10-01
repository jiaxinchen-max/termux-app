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
import com.termux.localgames.api.PrefixProvisionTasks;
import com.termux.localgames.api.RuntimeProvisionRequest;
import com.termux.localgames.data.FilePrefixProvisionTaskRepository;
import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.PrefixProvisionTask;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProvisionTaskState;
import com.termux.localgames.runtime.GlibcTermuxBoxBackend;
import com.termux.localgames.runtime.GameContainerFactory;
import com.termux.localgames.runtime.GameContainerProfileResolver;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent owner for Mobox-compatible initialization of game GLIBC Wine prefixes. */
public final class PrefixProvisionForegroundService extends Service {
    private static final String TAG = "GamesPrefixProvision";
    private static final String CHANNEL_ID = "games_prefix_provision";
    private static final int NOTIFICATION_ID = 23094;

    private final Set<String> submitted = new HashSet<>();
    private final AtomicInteger pendingCommands = new AtomicInteger();
    private ScheduledExecutorService executor;
    private GameStoragePaths paths;
    private FilePrefixProvisionTaskRepository tasks;
    private FileRuntimeProfileRepository profiles;
    private FileGameContainerRepository containers;
    private LocalGamesHost host;
    private volatile boolean receivedStart;

    @Override
    public void onCreate() {
        super.onCreate();
        paths = new GameStoragePaths(getFilesDir());
        tasks = new FilePrefixProvisionTaskRepository(paths.getPrefixProvisionTasksDirectory());
        profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        containers = new FileGameContainerRepository(paths.getContainersDirectory());
        host = LocalGames.requireHost(this);
        executor = Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "GamesPrefixProvision"));
        createChannel();
        startForeground(NOTIFICATION_ID, notification(null));
        executor.scheduleWithFixedDelay(this::reconcileAll, 500, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        receivedStart = true;
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();
        String taskId = intent.getStringExtra(PrefixProvisionTasks.EXTRA_TASK_ID);
        String gameId = intent.getStringExtra(PrefixProvisionTasks.EXTRA_GAME_ID);
        pendingCommands.incrementAndGet();
        executor.execute(() -> {
            try {
                if (PrefixProvisionTasks.ACTION_ENQUEUE.equals(action)) {
                    enqueue(requiredId(taskId), requiredId(gameId));
                } else if (PrefixProvisionTasks.ACTION_RECONCILE_ALL.equals(action)) {
                    reconcileAll();
                }
            } catch (Exception error) {
                Log.e(TAG, "Prefix provision command failed", error);
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

    private void enqueue(String taskId, String gameId) throws Exception {
        RuntimeProfile profile = resolveContainer(profiles.find(gameId)
            .orElseThrow(() -> new IOException("runtime_profile_missing")));
        if (profile.getRuntimeBackendType() != GameRuntimeBackendType.GLIBC_TERMUX_BOX) return;
        for (PrefixProvisionTask current : tasks.list()) {
            if (current.getGameId().equals(gameId) && !current.getState().isTerminal()) return;
        }
        PrefixProvisionTask task = PrefixProvisionTask.queued(taskId, gameId,
            profile.getWinePackage(), System.currentTimeMillis());
        tasks.save(task);
        prepareAndStart(task, profile);
    }

    private void prepareAndStart(PrefixProvisionTask task) throws Exception {
        RuntimeProfile profile = resolveContainer(profiles.find(task.getGameId())
            .orElseThrow(() -> new IOException("runtime_profile_missing")));
        if (profile.getRuntimeBackendType() != GameRuntimeBackendType.GLIBC_TERMUX_BOX ||
            !profile.getWinePackage().equals(task.getWinePackage())) {
            throw new IOException("prefix_profile_changed");
        }
        prepareAndStart(task, profile);
    }

    private void prepareAndStart(PrefixProvisionTask task, RuntimeProfile profile) throws Exception {
        if (!submitted.add(task.getTaskId())) return;
        try {
            PrefixProvisionTask preparing = task.getState() == RuntimeProvisionTaskState.QUEUED
                ? transition(task, RuntimeProvisionTaskState.PREPARING, "") : task;
            new GlibcRuntimeComponentPreparer(this, host).ensure(profile);
            if (isReady(preparing)) {
                PrefixProvisionTask verifying = transition(preparing,
                    RuntimeProvisionTaskState.VERIFYING, "");
                transition(verifying, RuntimeProvisionTaskState.SUCCEEDED, "");
                submitted.remove(task.getTaskId());
                return;
            }
            new LaunchScriptInstaller(this, paths).install(new GlibcTermuxBoxBackend());
            File script = new File(paths.getRuntimeDirectory(), "provision_glibc_prefix.sh");
            File spec = new File(paths.getPrefixProvisionSpecsDirectory(),
                task.getTaskId() + ".conf");
            File event = eventFile(task.getTaskId());
            File log = new File(paths.getPrefixProvisionLogsDirectory(),
                task.getTaskId() + ".log");
            writeSpec(spec, preparing, profile, event, log);
            host.startRuntimeProvision(new RuntimeProvisionRequest(task.getTaskId(),
                script.getCanonicalPath(), spec.getCanonicalPath(),
                paths.getPrefixProvisionDirectory().getCanonicalPath()));
            transition(preparing, RuntimeProvisionTaskState.BUILDING, "");
        } catch (Exception error) {
            submitted.remove(task.getTaskId());
            throw error;
        }
    }

    private void reconcileAll() {
        try {
            boolean active = false;
            for (PrefixProvisionTask task : tasks.list()) {
                if (task.getState().isTerminal()) continue;
                active = true;
                try { reconcile(task); }
                catch (Exception error) {
                    Log.e(TAG, "Prefix provision reconciliation failed", error);
                    fail(task.getTaskId(), stableError(error));
                }
            }
            if (receivedStart && pendingCommands.get() == 0 && !active) {
                stopForeground(false);
                stopSelf();
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to list prefix provision tasks", error);
        }
    }

    private void reconcile(PrefixProvisionTask task) throws Exception {
        Event event = readLastEvent(eventFile(task.getTaskId()));
        if (event == Event.SUCCEEDED || isReady(task)) {
            PrefixProvisionTask current = requiredTask(task.getTaskId());
            if (!current.getState().isTerminal()) {
                PrefixProvisionTask verifying = transition(current,
                    RuntimeProvisionTaskState.VERIFYING, "");
                if (!isReady(verifying)) throw new IOException("prefix_activation_invalid");
                transition(verifying, RuntimeProvisionTaskState.SUCCEEDED, "");
            }
            submitted.remove(task.getTaskId());
        } else if (event == Event.FAILED) {
            fail(task.getTaskId(), "prefix_bootstrap_failed");
            submitted.remove(task.getTaskId());
        } else if (!submitted.contains(task.getTaskId()) && !hasLiveBootstrap(task)) {
            prepareAndStart(task);
        }
    }

    private boolean isReady(PrefixProvisionTask task) throws IOException {
        RuntimeProfile profile = resolveContainer(profiles.find(task.getGameId())
            .orElseThrow(() -> new IOException("runtime_profile_missing")));
        File prefix = paths.getContainerPrefixDirectory(profile.getContainerId());
        if (!new File(prefix, ".termux-box-bootstrap-done").isFile() ||
            !new File(prefix, ".update-timestamp").exists()) return false;
        File runtime = new File(prefix, ".termux-box-wine-package");
        if (!runtime.isFile()) return true; // Compatible with prefixes created before this marker.
        try (BufferedReader reader = new BufferedReader(new FileReader(runtime))) {
            return task.getWinePackage().equals(reader.readLine());
        }
    }

    private boolean hasLiveBootstrap(PrefixProvisionTask task) throws IOException {
        RuntimeProfile profile = resolveContainer(profiles.find(task.getGameId())
            .orElseThrow(() -> new IOException("runtime_profile_missing")));
        File pidFile = new File(paths.getContainerPrefixDirectory(profile.getContainerId()).getPath() +
            ".bootstrap.lock/pid");
        if (!pidFile.isFile()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(pidFile))) {
            long pid = Long.parseLong(reader.readLine());
            if (host.isProcessAlive(pid)) return true;
        } catch (Exception ignored) { }
        File lock = pidFile.getParentFile();
        pidFile.delete();
        lock.delete();
        return false;
    }

    private RuntimeProfile resolveContainer(RuntimeProfile gameProfile) throws IOException {
        com.termux.localgames.domain.GameContainer container = containers.find(
            gameProfile.getContainerId()).orElse(null);
        if ("default".equals(gameProfile.getContainerId()) && (container == null ||
            container.getBackendType() !=
                com.termux.localgames.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX)) {
            container = GameContainerFactory.globalGlibcFromProfile(gameProfile);
            containers.save(container);
        }
        if (container == null) throw new IOException("bound_container_missing");
        return new GameContainerProfileResolver().resolve(gameProfile, container);
    }

    private void writeSpec(File target, PrefixProvisionTask task, RuntimeProfile profile,
                           File event, File log) throws IOException {
        ensureDirectory(target.getParentFile());
        ensureDirectory(event.getParentFile());
        ensureDirectory(log.getParentFile());
        if (event.exists() && !event.delete()) throw new IOException("prefix_event_reset_failed");
        File temporary = new File(target.getPath() + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(temporary, false),
            StandardCharsets.UTF_8)) {
            property(writer, "TERMUX_BOX_TASK_ID", task.getTaskId());
            property(writer, "TERMUX_BOX_EVENT_FILE", event.getCanonicalPath());
            property(writer, "TERMUX_BOX_LOG_FILE", log.getCanonicalPath());
            property(writer, "TERMUX_BOX_CONTAINER_NAME", profile.getContainerId());
            property(writer, "TERMUX_BOX_CONTAINER_PREFIX",
                paths.getContainerPrefixDirectory(profile.getContainerId()).getCanonicalPath());
            property(writer, "TERMUX_BOX_WINE_PACKAGE", profile.getWinePackage());
            property(writer, "TERMUX_BOX_RESOLUTION", profile.getResolution());
            property(writer, "TERMUX_BOX_BOX64_PRESET", profile.getBox64Preset());
            property(writer, "TERMUX_BOX_GRAPHICS_DRIVER", profile.getGraphicsDriver());
            property(writer, "TERMUX_BOX_DXWRAPPER", profile.getDxWrapper());
            property(writer, "TERMUX_BOX_DX_WRAPPER", profile.getDxWrapper());
            property(writer, "TERMUX_BOX_AUDIO_DRIVER", profile.getAudioDriver());
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("prefix_provision_spec_publish_failed");
        }
    }

    private static void property(Writer writer, String key, String value) throws IOException {
        writer.write(key);
        writer.write("='");
        writer.write(value.replace("'", "'\\''"));
        writer.write("'\n");
    }

    private PrefixProvisionTask transition(PrefixProvisionTask task,
                                            RuntimeProvisionTaskState state,
                                            String error) throws IOException {
        PrefixProvisionTask updated = task.transition(state, error, System.currentTimeMillis());
        tasks.save(updated);
        publish(updated);
        return updated;
    }

    private void fail(String taskId, String code) {
        if (taskId == null) return;
        try {
            PrefixProvisionTask task = tasks.find(taskId).orElse(null);
            if (task != null && !task.getState().isTerminal()) {
                transition(task, RuntimeProvisionTaskState.FAILED, code);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to persist prefix provision failure", error);
        }
    }

    private PrefixProvisionTask requiredTask(String taskId) throws IOException {
        return tasks.find(taskId)
            .orElseThrow(() -> new IOException("prefix_provision_task_missing"));
    }

    private File eventFile(String taskId) {
        return new File(paths.getPrefixProvisionEventsDirectory(), taskId + ".jsonl");
    }

    private static Event readLastEvent(File file) throws IOException {
        if (!file.isFile()) return Event.NONE;
        String last = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 4096) throw new IOException("prefix_event_too_large");
                if (!line.trim().isEmpty()) last = line;
            }
        }
        if (last == null) return Event.NONE;
        if (last.contains("\"state\":\"SUCCEEDED\"")) return Event.SUCCEEDED;
        if (last.contains("\"state\":\"FAILED\"")) return Event.FAILED;
        return Event.BUILDING;
    }

    private void publish(PrefixProvisionTask task) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(task));
    }

    private Notification notification(@Nullable PrefixProvisionTask task) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent target = new Intent(this, LocalGamesActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        String text = task == null ? getString(R.string.local_games_runtime_provision_preparing) :
            getString(R.string.local_games_runtime_provision_status,
                task.getGameId(), task.getState().name());
        return builder.setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.local_games_prefix_provision_title))
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
            getString(R.string.local_games_prefix_provision_channel),
            NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("prefix_provision_directory_failed");
        }
    }

    private static String requiredId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("invalid_prefix_provision_identifier");
        }
        return value;
    }

    private static String stableError(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z0-9_:.+-]{1,128}")
            ? message : "prefix_provision_failed";
    }

    private enum Event { NONE, BUILDING, SUCCEEDED, FAILED }
}
