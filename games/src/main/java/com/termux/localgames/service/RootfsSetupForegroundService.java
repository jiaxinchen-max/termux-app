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
import com.termux.localgames.api.RuntimeSetupRequest;
import com.termux.localgames.api.RuntimeSetupTasks;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.FileRuntimeSetupTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.RootfsSetupSpecCodec;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.RuntimeSetupTask;
import com.termux.localgames.domain.RuntimeSetupTaskState;
import com.termux.localgames.runtime.RootfsSetupRecipe;

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

/** Persistent owner for Termux-internal RootFS setup and activation. */
public final class RootfsSetupForegroundService extends Service {
    private static final String TAG = "GamesRootfsSetup";
    private static final String CHANNEL_ID = "games_runtime_setup";
    private static final int NOTIFICATION_ID = 23093;

    private final Set<String> submitted = new HashSet<>();
    private final AtomicInteger pendingCommands = new AtomicInteger();
    private volatile boolean receivedStart;
    private ScheduledExecutorService executor;
    private GameStoragePaths paths;
    private FileRuntimeSetupTaskRepository tasks;
    private ComponentInstallationReader components;
    private FileGameContainerRepository containers;
    private LocalGamesHost host;

    @Override
    public void onCreate() {
        super.onCreate();
        paths = new GameStoragePaths(getFilesDir());
        tasks = new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory());
        components = new ComponentInstallationReader(
            new ComponentStoragePaths(getFilesDir()).getInstallDirectory());
        containers = new FileGameContainerRepository(paths.getContainersDirectory());
        host = LocalGames.requireHost(this);
        executor = Executors.newSingleThreadScheduledExecutor(runnable ->
            new Thread(runnable, "GamesRootfsSetup"));
        createChannel();
        startForeground(NOTIFICATION_ID, notification(null));
        executor.scheduleWithFixedDelay(this::reconcileAll, 500, 1000, TimeUnit.MILLISECONDS);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        receivedStart = true;
        if (intent == null || intent.getAction() == null) return START_STICKY;
        String action = intent.getAction();
        String taskId = intent.getStringExtra(RuntimeSetupTasks.EXTRA_TASK_ID);
        String packageName = intent.getStringExtra(RuntimeSetupTasks.EXTRA_PACKAGE_NAME);
        String containerId = intent.getStringExtra(RuntimeSetupTasks.EXTRA_CONTAINER_ID);
        boolean baseOnly = intent.getBooleanExtra(RuntimeSetupTasks.EXTRA_BASE_ONLY, false);
        pendingCommands.incrementAndGet();
        executor.execute(() -> {
            try {
                if (RuntimeSetupTasks.ACTION_ENQUEUE.equals(action)) {
                    enqueue(requiredId(taskId), requiredId(packageName), requiredId(containerId),
                        baseOnly);
                } else if (RuntimeSetupTasks.ACTION_RECONCILE.equals(action)) {
                    reconcile(requiredTask(requiredId(taskId)));
                } else if (RuntimeSetupTasks.ACTION_RECONCILE_ALL.equals(action)) {
                    reconcileAll();
                }
            } catch (Exception error) {
                Log.e(TAG, "Setup command failed", error);
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

    private void enqueue(String taskId, String packageName, String containerId, boolean baseOnly)
        throws Exception {
        if (tasks.find(taskId).isPresent()) return;
        if (com.termux.localgames.domain.GameContainer.DEFAULT_ID.equals(containerId)) {
            throw new IOException("rootfs_container_must_be_independent");
        }
        RuntimeInstallationGate.requireRootfsSlot(getFilesDir(), taskId);
        RootfsSetupRecipe recipe = RootfsSetupRecipe.require(packageName);
        // Auto-download any base component that isn't installed yet, before the build starts.
        // No rootfs setup task is persisted at this point, so the component tasks this enqueues
        // pass their own RuntimeInstallationGate check; once they finish, resolveBaseComponents
        // succeeds instead of throwing rootfs_source_component_missing (the rebuild-stuck cause).
        new RootfsRuntimeComponentPreparer(this).ensure(recipe.getBaseComponentIds());
        java.util.LinkedHashMap<String, InstalledComponent> baseComponents =
            resolveBaseComponents(recipe);
        RootfsSetupAssetInstaller.Installed assets = new RootfsSetupAssetInstaller(this, paths)
            .install(recipe, componentSha256s(baseComponents));
        String containerName = containerId;
        RuntimeSetupTask task = RuntimeSetupTask.queued(taskId, packageName,
            recipe.getVersion(), assets.getRecipeSha256(), recipe.getSourceComponentId(),
            containerId, containerName, baseOnly, System.currentTimeMillis());
        tasks.save(task);
        prepareAndStart(task, baseComponents, assets);
    }

    private void prepareAndStart(RuntimeSetupTask task) throws Exception {
        RootfsSetupRecipe recipe = RootfsSetupRecipe.require(task.getPackageName());
        java.util.LinkedHashMap<String, InstalledComponent> baseComponents =
            resolveBaseComponents(recipe);
        RootfsSetupAssetInstaller.Installed assets = new RootfsSetupAssetInstaller(this, paths)
            .install(recipe, componentSha256s(baseComponents));
        if (!task.getRecipeSha256().equals(assets.getRecipeSha256())) {
            throw new IOException("rootfs_recipe_changed");
        }
        prepareAndStart(task, baseComponents, assets);
    }

    /** Resolves every active base component the recipe bakes into the shared image, keyed by id
     *  (which doubles as the in-container /opt/games-runtime/&lt;id&gt; dir for DXVK). */
    private java.util.LinkedHashMap<String, InstalledComponent> resolveBaseComponents(
        RootfsSetupRecipe recipe) throws IOException {
        java.util.LinkedHashMap<String, InstalledComponent> result = new java.util.LinkedHashMap<>();
        for (String id : recipe.getBaseComponentIds()) {
            InstalledComponent component = components.read(id).getActive()
                .orElseThrow(() -> new IOException("rootfs_source_component_missing:" + id));
            result.put(id, component);
        }
        return result;
    }

    private static String[] componentSha256s(
        java.util.LinkedHashMap<String, InstalledComponent> components) {
        String[] result = new String[components.size()];
        int index = 0;
        for (InstalledComponent component : components.values()) {
            result[index++] = component.getSha256();
        }
        return result;
    }

    private void prepareAndStart(RuntimeSetupTask task,
                                 java.util.LinkedHashMap<String, InstalledComponent> baseComponents,
                                 RootfsSetupAssetInstaller.Installed assets) throws Exception {
        if (!submitted.add(task.getTaskId())) return;
        try {
            RuntimeSetupTask preparing = transition(task,
                RuntimeSetupTaskState.PREPARING, "");
            File spec = new File(paths.getRuntimeSetupSpecsDirectory(),
                task.getTaskId() + ".setupspec");
            File events = eventFile(task.getTaskId());
            File log = new File(paths.getRuntimeSetupLogsDirectory(), task.getTaskId() + ".log");
            File context = new File(paths.getRuntimeSetupStagingDirectory(), task.getTaskId());
            // Empty for BASE_ONLY tasks (no per-game container/prefix to warm) -- the script
            // never binds a container prefix in that case. prefixWarmupScript is still needed
            // unconditionally though: BASE_ONLY also builds the shared template Wine prefix (see
            // setup_rootfs_runtime.sh), sourcing the same rootfs_prefix_warmup.sh functions.
            String winePackage = null;
            File winePrefixDirectory = null;
            File homeDirectory = null;
            File prefixWarmupScript = new LaunchScriptInstaller(this, paths)
                .installRootfsPrefixWarmup();
            if (!task.isBaseOnly()) {
                GameContainer container = containers.find(task.getContainerId())
                    .orElseThrow(() -> new IOException("rootfs_container_missing"));
                winePackage = container.getWinePackage();
                winePrefixDirectory = paths.getContainerPrefixDirectory(task.getContainerId(),
                    GameRuntimeBackendType.ROOTFS_PROOT);
                homeDirectory = paths.getContainerHomeDirectory(task.getContainerId(),
                    GameRuntimeBackendType.ROOTFS_PROOT);
            }
            java.util.LinkedHashMap<String, File> componentDirectories =
                new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, InstalledComponent> entry : baseComponents.entrySet()) {
                componentDirectories.put(entry.getKey(), entry.getValue().getDirectory());
            }
            new RootfsSetupSpecCodec().write(spec, preparing, assets.recipeDirectory,
                componentDirectories, context, events, log,
                winePackage, winePrefixDirectory, homeDirectory, prefixWarmupScript);
            host.startRuntimeSetup(new RuntimeSetupRequest(task.getTaskId(),
                assets.script.getCanonicalPath(), spec.getCanonicalPath(),
                paths.getRuntimeSetupDirectory().getCanonicalPath()));
            transition(preparing, RuntimeSetupTaskState.BUILDING, "");
        } catch (Exception error) {
            submitted.remove(task.getTaskId());
            throw error;
        }
    }

    private void reconcileAll() {
        try {
            List<RuntimeSetupTask> snapshots = tasks.list();
            boolean active = false;
            for (RuntimeSetupTask task : snapshots) {
                if (task.getState().isTerminal()) continue;
                active = true;
                try { reconcile(task); }
                catch (Exception error) {
                    Log.e(TAG, "Setup reconciliation failed", error);
                    fail(task.getTaskId(), stableError(error));
                }
            }
            if (receivedStart && pendingCommands.get() == 0 && !active) {
                stopForeground(false);
                stopSelf();
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to list setup tasks", error);
        }
    }

    private void reconcile(RuntimeSetupTask task) throws Exception {
        SetupEvent event = readLastEvent(eventFile(task.getTaskId()));
        // The script's own emitted SUCCEEDED event is the only completion signal -- there is no
        // more per-container "activation" to cross-check against now that every container mounts
        // the one shared, always-current RootFS (see GameStoragePaths.getSharedRootfsDirectory()).
        if (event.state == Event.SUCCEEDED) {
            RuntimeSetupTask current = requiredTask(task.getTaskId());
            if (!current.getState().isTerminal()) {
                RuntimeSetupTask verifying = transition(current,
                    RuntimeSetupTaskState.VERIFYING, "");
                RuntimeSetupTask activating = transition(verifying,
                    RuntimeSetupTaskState.ACTIVATING, "");
                transition(activating, RuntimeSetupTaskState.SUCCEEDED, "");
            }
            submitted.remove(task.getTaskId());
        } else if (event.state == Event.FAILED) {
            fail(task.getTaskId(), event.errorCode);
            submitted.remove(task.getTaskId());
        } else if (!submitted.contains(task.getTaskId())) {
            prepareAndStart(task);
        }
    }

    private RuntimeSetupTask transition(RuntimeSetupTask task,
                                            RuntimeSetupTaskState state,
                                            String error) throws IOException {
        RuntimeSetupTask updated = task.transition(state, error, System.currentTimeMillis());
        tasks.save(updated);
        publish(updated);
        return updated;
    }

    private void fail(String taskId, String code) {
        if (taskId == null) return;
        try {
            RuntimeSetupTask task = tasks.find(taskId).orElse(null);
            if (task != null && !task.getState().isTerminal()) {
                transition(task, RuntimeSetupTaskState.FAILED, code);
            }
        } catch (Exception error) {
            Log.e(TAG, "Unable to persist setup failure", error);
        }
    }

    private RuntimeSetupTask requiredTask(String taskId) throws IOException {
        return tasks.find(taskId).orElseThrow(() -> new IOException("setup_task_missing"));
    }

    private File eventFile(String taskId) {
        return new File(paths.getRuntimeSetupEventsDirectory(), taskId + ".jsonl");
    }

    private static SetupEvent readLastEvent(File file) throws IOException {
        if (!file.isFile()) return SetupEvent.none();
        String last = null;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > 4096) throw new IOException("setup_event_too_large");
                if (!line.trim().isEmpty()) last = line;
            }
        }
        if (last == null) return SetupEvent.none();
        try {
            JSONObject value = new JSONObject(last);
            String state = value.optString("state", "BUILDING");
            if ("SUCCEEDED".equals(state)) {
                return new SetupEvent(Event.SUCCEEDED, "");
            }
            if ("FAILED".equals(state)) {
                String code = value.optString("errorCode", "setup_script_failed");
                if (!code.matches("[a-z0-9_:.+-]{1,128}")) code = "setup_script_failed";
                return new SetupEvent(Event.FAILED, code);
            }
            return new SetupEvent(Event.BUILDING, "");
        } catch (JSONException error) {
            throw new IOException("setup_event_invalid", error);
        }
    }

    private void publish(RuntimeSetupTask task) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .notify(NOTIFICATION_ID, notification(task));
    }

    private void publishMessage(String message) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(
            NOTIFICATION_ID, builder.setSmallIcon(R.drawable.local_games_ic_component)
                .setContentTitle(getString(R.string.local_games_runtime_setup_title))
                .setContentText(message).setOngoing(false).build());
    }

    private Notification notification(@Nullable RuntimeSetupTask task) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        Intent target = new Intent(this, LocalGamesActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        String text = task == null ? getString(R.string.local_games_runtime_setup_preparing) :
            getString(R.string.local_games_runtime_setup_status,
                task.getPackageName(), task.getState().name());
        return builder.setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.local_games_runtime_setup_title))
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
            getString(R.string.local_games_runtime_setup_channel),
            NotificationManager.IMPORTANCE_LOW);
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
            .createNotificationChannel(channel);
    }

    private static String requiredId(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("invalid_setup_identifier");
        }
        return value;
    }

    private static String stableError(Exception error) {
        String message = error.getMessage();
        return message != null && message.matches("[a-z0-9_:.+-]{1,128}")
            ? message : "runtime_setup_failed";
    }

    private enum Event { NONE, BUILDING, SUCCEEDED, FAILED }

    private static final class SetupEvent {
        final Event state;
        final String errorCode;

        SetupEvent(Event state, String errorCode) {
            this.state = state;
            this.errorCode = errorCode;
        }

        static SetupEvent none() {
            return new SetupEvent(Event.NONE, "");
        }
    }
}
