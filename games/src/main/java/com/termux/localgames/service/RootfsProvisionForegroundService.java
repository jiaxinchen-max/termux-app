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
import com.termux.localgames.api.RuntimeProvisionRequest;
import com.termux.localgames.api.RuntimeProvisionTasks;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.data.FileRuntimeProvisionTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.RootfsProvisionSpecCodec;
import com.termux.localgames.domain.RuntimeProvisionTask;
import com.termux.localgames.domain.RuntimeProvisionTaskState;
import com.termux.localgames.runtime.RootfsProvisionRecipe;
import com.termux.localgames.runtime.RootfsRuntimeInstallation;
import com.termux.localgames.runtime.RootfsRuntimeInstallationReader;
import com.termux.localgames.runtime.RootfsRuntimeActivationStore;

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

/** Persistent owner for Termux-internal RootFS provisioning and activation. */
public final class RootfsProvisionForegroundService extends Service {
    private static final String TAG = "GamesRootfsProvision";
    private static final String CHANNEL_ID = "games_runtime_provision";
    private static final int NOTIFICATION_ID = 23093;

    private final Set<String> submitted = new HashSet<>();
    private final AtomicInteger pendingCommands = new AtomicInteger();
    private volatile boolean receivedStart;
    private ScheduledExecutorService executor;
    private GameStoragePaths paths;
    private FileRuntimeProvisionTaskRepository tasks;
    private ComponentInstallationReader components;
    private LocalGamesHost host;

    @Override
    public void onCreate() {
        super.onCreate();
        paths = new GameStoragePaths(getFilesDir());
        tasks = new FileRuntimeProvisionTaskRepository(paths.getRuntimeProvisionTasksDirectory());
        components = new ComponentInstallationReader(
            new ComponentStoragePaths(getFilesDir()).getInstallDirectory());
        host = LocalGames.requireHost(this);
        executor = Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "GamesRootfsProvision"));
        createChannel();
        startForeground(NOTIFICATION_ID, notification(null));
        executor.scheduleWithFixedDelay(this::reconcileAll, 500, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        receivedStart = true;
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();
        String taskId = intent.getStringExtra(RuntimeProvisionTasks.EXTRA_TASK_ID);
        String packageName = intent.getStringExtra(RuntimeProvisionTasks.EXTRA_PACKAGE_NAME);
        pendingCommands.incrementAndGet();
        executor.execute(() -> {
            try {
                if (RuntimeProvisionTasks.ACTION_ENQUEUE.equals(action)) {
                    enqueue(requiredId(taskId), requiredId(packageName));
                } else if (RuntimeProvisionTasks.ACTION_RECONCILE.equals(action)) {
                    reconcile(requiredTask(requiredId(taskId)));
                } else if (RuntimeProvisionTasks.ACTION_RECONCILE_ALL.equals(action)) {
                    reconcileAll();
                } else if (RuntimeProvisionTasks.ACTION_ROLLBACK.equals(action)) {
                    RuntimeInstallationGate.requireRootfsSlot(getFilesDir(), null);
                    RootfsRuntimeInstallation active = new RootfsRuntimeActivationStore(paths)
                        .rollback(requiredId(packageName));
                    publishMessage(getString(R.string.local_games_runtime_rollback_complete,
                        active.getVersion()));
                }
            } catch (Exception error) {
                Log.e(TAG, "Provision command failed", error);
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

    private void enqueue(String taskId, String packageName) throws Exception {
        if (tasks.find(taskId).isPresent()) return;
        RuntimeInstallationGate.requireRootfsSlot(getFilesDir(), taskId);
        RootfsProvisionRecipe recipe = RootfsProvisionRecipe.require(packageName);
        InstalledComponent source = components.read(recipe.getSourceComponentId()).getActive()
            .orElseThrow(() -> new IOException("rootfs_source_component_missing:" +
                recipe.getSourceComponentId()));
        RootfsProvisionAssetInstaller.Installed assets =
            new RootfsProvisionAssetInstaller(this, paths).install(recipe, source.getSha256());
        String containerName = recipe.getContainerPrefix() + assets.recipeSha256.substring(0, 12);
        RuntimeProvisionTask task = RuntimeProvisionTask.queued(taskId, packageName,
            recipe.getVersion(), assets.recipeSha256, recipe.getSourceComponentId(),
            containerName, System.currentTimeMillis());
        tasks.save(task);
        prepareAndStart(task, source, assets);
    }

    private void prepareAndStart(RuntimeProvisionTask task) throws Exception {
        RootfsProvisionRecipe recipe = RootfsProvisionRecipe.require(task.getPackageName());
        InstalledComponent source = components.read(task.getSourceComponentId()).getActive()
            .orElseThrow(() -> new IOException("rootfs_source_component_missing:" +
                task.getSourceComponentId()));
        RootfsProvisionAssetInstaller.Installed assets =
            new RootfsProvisionAssetInstaller(this, paths).install(recipe, source.getSha256());
        if (!task.getRecipeSha256().equals(assets.recipeSha256)) {
            throw new IOException("rootfs_recipe_changed");
        }
        prepareAndStart(task, source, assets);
    }

    private void prepareAndStart(RuntimeProvisionTask task, InstalledComponent source,
                                 RootfsProvisionAssetInstaller.Installed assets) throws Exception {
        if (!submitted.add(task.getTaskId())) return;
        try {
            RuntimeProvisionTask preparing = transition(task,
                RuntimeProvisionTaskState.PREPARING, "");
            File spec = new File(paths.getRuntimeProvisionSpecsDirectory(),
                task.getTaskId() + ".provisionspec");
            File events = eventFile(task.getTaskId());
            File log = new File(paths.getRuntimeProvisionLogsDirectory(), task.getTaskId() + ".log");
            File context = new File(paths.getRuntimeProvisionStagingDirectory(), task.getTaskId());
            new RootfsProvisionSpecCodec().write(spec, preparing, assets.recipeDirectory,
                source.getDirectory(), context, paths.getRootfsRuntimeDirectory(), events, log);
            host.startRuntimeProvision(new RuntimeProvisionRequest(task.getTaskId(),
                assets.script.getCanonicalPath(), spec.getCanonicalPath(),
                paths.getRuntimeProvisionDirectory().getCanonicalPath()));
            transition(preparing, RuntimeProvisionTaskState.BUILDING, "");
        } catch (Exception error) {
            submitted.remove(task.getTaskId());
            throw error;
        }
    }

    private void reconcileAll() {
        try {
            List<RuntimeProvisionTask> snapshots = tasks.list();
            boolean active = false;
            for (RuntimeProvisionTask task : snapshots) {
                if (task.getState().isTerminal()) continue;
                active = true;
                try { reconcile(task); }
                catch (Exception error) {
                    Log.e(TAG, "Provision reconciliation failed", error);
                    fail(task.getTaskId(), stableError(error));
                }
            }
            if (receivedStart && pendingCommands.get() == 0 && !active) {
                stopForeground(false);
                stopSelf();
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to list provision tasks", error);
        }
    }

    private void reconcile(RuntimeProvisionTask task) throws Exception {
        ProvisionEvent event = readLastEvent(eventFile(task.getTaskId()));
        if (event.state == Event.SUCCEEDED || activeMatches(task)) {
            RuntimeProvisionTask current = requiredTask(task.getTaskId());
            if (!current.getState().isTerminal()) {
                RuntimeProvisionTask verifying = transition(current,
                    RuntimeProvisionTaskState.VERIFYING, "");
                if (!activeMatches(verifying)) throw new IOException("rootfs_activation_invalid");
                RuntimeProvisionTask activating = transition(verifying,
                    RuntimeProvisionTaskState.ACTIVATING, "");
                transition(activating, RuntimeProvisionTaskState.SUCCEEDED, "");
            }
            submitted.remove(task.getTaskId());
        } else if (event.state == Event.FAILED) {
            fail(task.getTaskId(), event.errorCode);
            submitted.remove(task.getTaskId());
        } else if (!submitted.contains(task.getTaskId())) {
            prepareAndStart(task);
        }
    }

    private boolean activeMatches(RuntimeProvisionTask task) {
        try {
            RootfsRuntimeInstallation active = new RootfsRuntimeInstallationReader(paths)
                .readActive(task.getPackageName()).orElse(null);
            return active != null && active.getVersion() == task.getVersion() &&
                active.getRecipeSha256().equals(task.getRecipeSha256()) &&
                active.getContainerName().equals(task.getContainerName());
        } catch (IOException error) {
            return false;
        }
    }

    private RuntimeProvisionTask transition(RuntimeProvisionTask task,
                                            RuntimeProvisionTaskState state,
                                            String error) throws IOException {
        RuntimeProvisionTask updated = task.transition(state, error, System.currentTimeMillis());
        tasks.save(updated);
        publish(updated);
        return updated;
    }

    private void fail(String taskId, String code) {
        if (taskId == null) return;
        try {
            RuntimeProvisionTask task = tasks.find(taskId).orElse(null);
            if (task != null && !task.getState().isTerminal()) {
                transition(task, RuntimeProvisionTaskState.FAILED, code);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to persist provision failure", error);
        }
    }

    private RuntimeProvisionTask requiredTask(String taskId) throws IOException {
        return tasks.find(taskId).orElseThrow(() -> new IOException("provision_task_missing"));
    }

    private File eventFile(String taskId) {
        return new File(paths.getRuntimeProvisionEventsDirectory(), taskId + ".jsonl");
    }

    private static ProvisionEvent readLastEvent(File file) throws IOException {
        if (!file.isFile()) return ProvisionEvent.none();
        String last = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 4096) throw new IOException("provision_event_too_large");
                if (!line.trim().isEmpty()) last = line;
            }
        }
        if (last == null) return ProvisionEvent.none();
        try {
            JSONObject value = new JSONObject(last);
            String state = value.optString("state", "BUILDING");
            if ("SUCCEEDED".equals(state)) {
                return new ProvisionEvent(Event.SUCCEEDED, "");
            }
            if ("FAILED".equals(state)) {
                String code = value.optString("errorCode", "provision_script_failed");
                if (!code.matches("[a-z0-9_:.+-]{1,128}")) code = "provision_script_failed";
                return new ProvisionEvent(Event.FAILED, code);
            }
            return new ProvisionEvent(Event.BUILDING, "");
        } catch (JSONException error) {
            throw new IOException("provision_event_invalid", error);
        }
    }

    private void publish(RuntimeProvisionTask task) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(task));
    }

    private void publishMessage(String message) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(
            NOTIFICATION_ID, builder.setSmallIcon(R.drawable.local_games_ic_component)
                .setContentTitle(getString(R.string.local_games_runtime_provision_title))
                .setContentText(message).setOngoing(false).build());
    }

    private Notification notification(@Nullable RuntimeProvisionTask task) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent target = new Intent(this, LocalGamesActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        String text = task == null ? getString(R.string.local_games_runtime_provision_preparing) :
            getString(R.string.local_games_runtime_provision_status,
                task.getPackageName(), task.getState().name());
        return builder.setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.local_games_runtime_provision_title))
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
            throw new IOException("invalid_provision_identifier");
        }
        return value;
    }

    private static String stableError(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z0-9_:.+-]{1,128}")
            ? message : "runtime_provision_failed";
    }

    private enum Event { NONE, BUILDING, SUCCEEDED, FAILED }

    private static final class ProvisionEvent {
        final Event state;
        final String errorCode;

        ProvisionEvent(Event state, String errorCode) {
            this.state = state;
            this.errorCode = errorCode;
        }

        static ProvisionEvent none() {
            return new ProvisionEvent(Event.NONE, "");
        }
    }
}
