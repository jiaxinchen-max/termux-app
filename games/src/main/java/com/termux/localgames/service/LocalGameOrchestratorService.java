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
import com.termux.localgames.activity.GameLaunchActivity;
import com.termux.localgames.activity.LocalGamesActivity;
import com.termux.localgames.api.LaunchRequest;
import com.termux.localgames.api.LaunchTasks;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.api.PrefixProvisionTasks;
import com.termux.localgames.api.ResolvedGameDirectory;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.FileLaunchTaskRepository;
import com.termux.localgames.data.FilePrefixProvisionTaskRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.LaunchSpecCodec;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.LaunchEvent;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;
import com.termux.localgames.domain.PrefixProvisionTask;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.runtime.LaunchEventLogReader;
import com.termux.localgames.runtime.LaunchPreflightResult;
import com.termux.localgames.runtime.LaunchSpecFactory;
import com.termux.localgames.runtime.LaunchTaskStateMachine;
import com.termux.localgames.runtime.GameContainerFactory;
import com.termux.localgames.runtime.GameContainerProfileResolver;
import com.termux.localgames.runtime.PreflightIssue;
import com.termux.localgames.runtime.GameRuntimeBackend;
import com.termux.localgames.runtime.GameRuntimeBackendRegistry;
import com.termux.localgames.components.ComponentStoragePaths;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Persistent owner for launch preparation, Termux runner submission and JSONL reconciliation. */
public final class LocalGameOrchestratorService extends Service {

    private static final String TAG = "GamesLaunchService";
    private static final String CHANNEL_ID = "games_launch_tasks";
    private static final int NOTIFICATION_ID = 23092;
    private static final long HOST_START_GRACE_MS = 30000;
    private static final long HOST_EXIT_GRACE_MS = 2000;
    private static final long PREFIX_PREPARE_TIMEOUT_MS = 45L * 60L * 1000L;

    private final Set<String> preparing = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> submitted = Collections.synchronizedSet(new HashSet<>());
    private ScheduledExecutorService executor;
    private GameStoragePaths paths;
    private FileLaunchTaskRepository tasks;
    private FileGameRepository games;
    private FileRuntimeProfileRepository profiles;
    private FileGameContainerRepository containers;
    private LocalGamesHost host;
    private LaunchEventLogReader eventReader;
    private LaunchTaskStateMachine stateMachine;
    private GameRuntimeBackendRegistry backendRegistry;
    private ComponentStoragePaths componentPaths;

    @Override
    public void onCreate() {
        super.onCreate();
        paths = new GameStoragePaths(getFilesDir());
        tasks = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
        games = new FileGameRepository(paths.getLibraryDirectory());
        profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        containers = new FileGameContainerRepository(paths.getContainersDirectory());
        host = LocalGames.requireHost(this);
        eventReader = new LaunchEventLogReader();
        stateMachine = new LaunchTaskStateMachine();
        backendRegistry = GameRuntimeBackendRegistry.createDefault();
        componentPaths = new ComponentStoragePaths(getFilesDir());
        executor = Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "GamesLaunchOrchestrator"));
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, notification(null));
        executor.scheduleWithFixedDelay(this::reconcileAll, 0, 500, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();
        String taskId = intent.getStringExtra(LaunchTasks.EXTRA_TASK_ID);
        executor.execute(() -> {
            try {
                if (LaunchTasks.ACTION_EXECUTE.equals(action)) prepareAndStart(requiredId(taskId));
                else if (LaunchTasks.ACTION_CANCEL.equals(action)) {
                    cancel(requiredId(taskId), intent.getBooleanExtra(LaunchTasks.EXTRA_FORCE, false));
                } else if (LaunchTasks.ACTION_RECONCILE.equals(action)) reconcileAll();
                else if (LaunchTasks.ACTION_DISPLAY_CONNECTION.equals(action)) {
                    updateDisplayConnection(requiredId(taskId),
                        intent.getBooleanExtra(LaunchTasks.EXTRA_DISPLAY_CONNECTED, false));
                } else if (LaunchTasks.ACTION_FIRST_FRAME.equals(action)) {
                    confirmFirstFrame(requiredId(taskId));
                }
            } catch (Exception error) {
                Log.e(TAG, "Launch command failed: " + action, error);
                if (taskId != null) failTask(taskId, stableError(error), true);
            }
        });
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private void prepareAndStart(String taskId) throws Exception {
        if (submitted.contains(taskId)) return;
        if (!preparing.add(taskId)) return;
        try {
            LaunchTask task = requiredTask(taskId);
            if (task.getState().isTerminal()) return;
            File cancel = cancelFile(taskId);
            if (cancel.isFile()) {
                cancelTask(task, "cancelled");
                return;
            }
            File specFile = specFile(taskId);
            if (task.getState() != LaunchTaskState.QUEUED) return;
            host.prepareLaunchRuntime();
            com.termux.localgames.domain.LaunchSpec spec;
            LaunchSpecCodec codec = new LaunchSpecCodec();
            if (!specFile.isFile() || task.getEnvironmentFingerprint().isEmpty()) {
                Game game = games.find(task.getGameId()).orElseThrow(() ->
                    new IOException("game_not_found"));
                RuntimeProfile gameProfile = profiles.find(game.getId()).orElseGet(() ->
                    RuntimeProfilePresets.create(game.getId(), RuntimeProfilePreset.RECOMMENDED));
                RuntimeProfile profile = resolveContainer(gameProfile);
                GameRuntimeBackend backend = backendRegistry.require(profile.getRuntimeBackendType());
                if (profile.getRuntimeBackendType() == GameRuntimeBackendType.GLIBC_TERMUX_BOX &&
                    !ensureGlibcPrefix(taskId, game, profile)) {
                    return;
                }
                LaunchPreflightResult preflight = new AndroidLaunchPreflight(this)
                    .evaluate(game, profile, host);
                if (!preflight.isReady()) {
                    PreflightIssue issue = preflight.getIssues().get(0);
                    failTask(taskId, preflightError(issue), true);
                    return;
                }
                ResolvedGameDirectory resolved = host.resolveGameDirectory(game.getRootUri());
                if (!resolved.isResolved()) {
                    failTask(taskId, resolved.getErrorCode(), true);
                    return;
                }
                String runtimeRoot = backend.resolveRuntimeRoot(paths, componentPaths, profile);
                spec = new LaunchSpecFactory(paths)
                    .create(taskId, game, profile, resolved.getPath(), backend, runtimeRoot);
                codec.write(specFile, spec);
                task = task.withEnvironmentFingerprint(codec.fingerprint(spec),
                    System.currentTimeMillis());
                tasks.save(task);
            } else {
                spec = codec.read(specFile);
            }
            GameRuntimeBackend backend = backendRegistry.require(spec.getRuntimeBackendType());
            File script = new LaunchScriptInstaller(this, paths).install(backend);
            task = task.touch(Math.max(task.getUpdatedAt(), System.currentTimeMillis()));
            tasks.save(task);
            host.startLaunch(new LaunchRequest(taskId, script.getCanonicalPath(),
                specFile.getCanonicalPath(), paths.getRuntimeDirectory().getCanonicalPath(),
                spec.getLaunchExecutionMode()));
            submitted.add(taskId);
            publish(task);
        } finally {
            preparing.remove(taskId);
        }
    }

    private void cancel(String taskId, boolean force) throws Exception {
        LaunchTask task = requiredTask(taskId);
        if (task.getState().isTerminal()) return;
        if (task.getPid() <= 0) {
            cancelTask(task, "cancelled_before_host_start");
            return;
        }
        if (force) {
            host.stopLaunch(task.getPid(), true);
            waitForHostExit(task.getPid(), 500);
            cancelTask(task, "force_cancelled");
            cleanupKnownLock(taskId, task.getPid());
        } else {
            host.stopLaunch(task.getPid(), false);
        }
    }

    private void updateDisplayConnection(String taskId, boolean connected) throws IOException {
        LaunchTask current = requiredTask(taskId);
        LaunchTask updated = stateMachine.observeDisplayConnection(current, connected,
            System.currentTimeMillis());
        if (updated != current) {
            tasks.save(updated);
            publish(updated);
        }
    }

    private void confirmFirstFrame(String taskId) throws IOException {
        LaunchTask current = requiredTask(taskId);
        LaunchTask updated = stateMachine.confirmFirstFrame(current, System.currentTimeMillis());
        if (updated != current) {
            tasks.save(updated);
            publish(updated);
        }
    }

    private void reconcileAll() {
        try {
            List<LaunchTask> snapshots = tasks.list();
            boolean active = false;
            for (LaunchTask task : snapshots) {
                if (task.getState().isTerminal()) continue;
                active = true;
                try {
                    reconcile(task);
                } catch (Exception error) {
                    Log.e(TAG, "Unable to reconcile launch: " + task.getTaskId(), error);
                    failTask(task.getTaskId(), error instanceof IOException
                        ? "launch_event_invalid" : stableError(error), true);
                }
            }
            if (!active && preparing.isEmpty()) {
                stopForeground(false);
                stopSelf();
            }
        } catch (Exception error) {
            Log.e(TAG, "Launch reconciliation failed", error);
        }
    }

    private void reconcile(LaunchTask snapshot) throws Exception {
        LaunchTask current = snapshot;
        File eventFile = eventFile(current.getTaskId());
        for (LaunchEvent event : eventReader.readAfter(eventFile, current.getLastEventSequence())) {
            current = stateMachine.apply(current, event);
            tasks.save(current);
            publish(current);
            if (current.getState().isTerminal()) {
                onTerminal(current);
                return;
            }
        }
        long age = System.currentTimeMillis() - current.getUpdatedAt();
        if (current.getPid() > 0 && !host.isProcessAlive(current.getPid()) &&
            age >= HOST_EXIT_GRACE_MS) {
            failTask(current.getTaskId(), "host_process_lost", true);
            cleanupKnownLock(current.getTaskId(), current.getPid());
        } else if (current.getPid() < 0 && current.getState() == LaunchTaskState.QUEUED &&
            !submitted.contains(current.getTaskId())) {
            prepareAndStart(current.getTaskId());
        } else if (current.getPid() < 0 && age >= HOST_START_GRACE_MS) {
            failTask(current.getTaskId(), "host_start_timeout", true);
        }
    }

    private void onTerminal(LaunchTask task) {
        submitted.remove(task.getTaskId());
        if (task.getState() == LaunchTaskState.SUCCEEDED) {
            try {
                profiles.find(task.getGameId()).ifPresent(profile -> {
                    try { profiles.saveLastSuccessful(profile); }
                    catch (IOException error) { Log.w(TAG, "Unable to persist success profile", error); }
                });
                games.find(task.getGameId()).ifPresent(game -> {
                    try { games.save(game.withLastPlayedAt(System.currentTimeMillis())); }
                    catch (IOException error) { Log.w(TAG, "Unable to update last played", error); }
                });
            } catch (IOException error) {
                Log.w(TAG, "Unable to apply launch success metadata", error);
            }
        }
        File marker = cancelFile(task.getTaskId());
        if (marker.isFile() && !marker.delete()) Log.w(TAG, "Unable to remove cancel marker");
    }

    private void failTask(String taskId, String errorCode, boolean recoverable) {
        try {
            LaunchTask task = requiredTask(taskId);
            if (task.getState().isTerminal()) return;
            LaunchEvent event = new LaunchEvent(taskId, task.getLastEventSequence() + 1,
                LaunchTaskState.FAILED, LaunchStage.COMPLETE, 100,
                Math.max(System.currentTimeMillis(), task.getUpdatedAt()), task.getPid(), null,
                errorCode, recoverable, errorCode, task.getLogRef());
            LaunchTask failed = stateMachine.apply(task, event);
            tasks.save(failed);
            publish(failed);
            onTerminal(failed);
        } catch (Exception persistenceError) {
            Log.e(TAG, "Unable to persist launch failure: " + taskId, persistenceError);
        }
    }

    private void cancelTask(LaunchTask task, String message) throws IOException {
        LaunchEvent event = new LaunchEvent(task.getTaskId(), task.getLastEventSequence() + 1,
            LaunchTaskState.CANCELLED, LaunchStage.COMPLETE, 100,
            Math.max(System.currentTimeMillis(), task.getUpdatedAt()), task.getPid(), null,
            "", false, message, task.getLogRef());
        LaunchTask cancelled = stateMachine.apply(task, event);
        tasks.save(cancelled);
        publish(cancelled);
        onTerminal(cancelled);
    }

    private void cleanupKnownLock(String taskId, long expectedPid) {
        File lock = new File(paths.getLaunchLocksDirectory(), taskId);
        File pid = new File(lock, "pid");
        File config = new File(lock, "bootstrap.conf");
        if (pid.isFile() && !pid.delete()) Log.w(TAG, "Unable to remove task lock pid");
        if (config.isFile() && !config.delete()) Log.w(TAG, "Unable to remove bootstrap config");
        if (lock.isDirectory() && !lock.delete()) Log.w(TAG, "Unable to remove task lock");
        File global = new File(getFilesDir(), "usr/glibc/termux-box/run/glibc-wine.lock");
        File globalPid = new File(global, "pid");
        try (java.io.BufferedReader reader = globalPid.isFile()
            ? new java.io.BufferedReader(new java.io.FileReader(globalPid)) : null) {
            if (reader != null && Long.toString(expectedPid).equals(reader.readLine()) &&
                !host.isProcessAlive(expectedPid)) {
                if (!globalPid.delete()) Log.w(TAG, "Unable to remove global lock pid");
                if (global.isDirectory() && !global.delete()) Log.w(TAG, "Unable to remove global lock");
            }
        } catch (IOException error) {
            Log.w(TAG, "Unable to inspect global launch lock", error);
        }
    }

    private void waitForHostExit(long pid, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (host.isProcessAlive(pid) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(25); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean ensureGlibcPrefix(String launchTaskId, Game game, RuntimeProfile profile)
        throws Exception {
        FilePrefixProvisionTaskRepository prefixTasks = new FilePrefixProvisionTaskRepository(
            paths.getPrefixProvisionTasksDirectory());
        String provisionTaskId = PrefixProvisionTasks.enqueue(this, game.getId());
        long deadline = System.currentTimeMillis() + PREFIX_PREPARE_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            if (cancelFile(launchTaskId).isFile()) {
                cancelTask(requiredTask(launchTaskId), "cancelled_during_runtime_prepare");
                return false;
            }
            PrefixProvisionTask provision = prefixTasks.find(provisionTaskId).orElse(null);
            if (provision != null) {
                if (provision.getState() ==
                    com.termux.localgames.domain.RuntimeProvisionTaskState.SUCCEEDED) {
                    return true;
                }
                if (provision.getState() ==
                    com.termux.localgames.domain.RuntimeProvisionTaskState.FAILED) {
                    String error = provision.getErrorCode();
                    throw new IOException(error.isEmpty()
                        ? "prefix_provision_failed" : error);
                }
                if (provision.getState() ==
                    com.termux.localgames.domain.RuntimeProvisionTaskState.CANCELLED) {
                    throw new IOException("prefix_provision_cancelled");
                }
            }
            sleepForRuntimePreparation();
        }
        throw new IOException("prefix_provision_timeout");
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

    private static void sleepForRuntimePreparation() throws IOException {
        try {
            Thread.sleep(250L);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("runtime_prepare_interrupted", error);
        }
    }

    private LaunchTask requiredTask(String taskId) throws IOException {
        return tasks.find(taskId).orElseThrow(() -> new IOException("launch_task_not_found"));
    }

    private static String requiredId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("invalid_launch_task_id");
        }
        return value;
    }

    private File specFile(String taskId) { return new File(paths.getLaunchSpecsDirectory(), taskId + ".launchspec"); }
    private File eventFile(String taskId) { return new File(paths.getLaunchEventsDirectory(), taskId + ".jsonl"); }
    private File cancelFile(String taskId) { return new File(paths.getLaunchCancelDirectory(), taskId + ".cancel"); }

    private void publish(LaunchTask task) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(task));
    }

    private Notification notification(@Nullable LaunchTask task) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent target = task == null ? new Intent(this, LocalGamesActivity.class) :
            GameLaunchActivity.createTaskIntent(this, task.getGameId(), task.getTaskId());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        String text = task == null ? getString(R.string.local_game_launch_preparing) :
            getString(R.string.local_game_launch_notification_status,
                task.getStage().name(), task.getProgress());
        return builder.setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.local_game_launch_notification_title))
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this,
                task == null ? 0 : task.getTaskId().hashCode(), target, flags))
            .setOnlyAlertOnce(true)
            .setOngoing(task == null || !task.getState().isTerminal())
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, task == null ? 0 : task.getProgress(), task == null)
            .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            getString(R.string.local_game_launch_channel), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.local_game_launch_channel_description));
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }

    private static String stableError(Exception error) {
        String message = error.getMessage();
        if (message != null && message.matches("[a-z0-9_:.+-]{1,128}")) return message;
        return "launch_orchestration_failed";
    }

    static String preflightError(PreflightIssue issue) {
        String code = "preflight_" + issue.getCode().name().toLowerCase(Locale.US);
        String subject = issue.getSubject();
        return subject != null && subject.matches("[A-Za-z0-9._:-]{1,128}")
            ? code + ":" + subject : code;
    }
}
