package com.termux.ui.wine.service;

import com.termux.ui.wine.runtime.RuntimeEditingStop;
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

import com.termux.ui.R;
import com.termux.ui.activity.LocalGamesActivity;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.api.RuntimeSetupRequest;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.install.ComponentInstallationReader;
import com.termux.ui.components.install.InstalledComponent;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.service.LaunchScriptInstaller;
import com.termux.ui.service.RuntimeInstallationGate;
import com.termux.ui.service.SetupPreparationLog;
import com.termux.ui.wine.api.RuntimeSetupTasks;
import com.termux.ui.wine.data.FileGameContainerRepository;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.data.RootfsSetupSpecCodec;
import com.termux.ui.wine.domain.GameContainer;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeSetupTask;
import com.termux.ui.wine.domain.RuntimeSetupTaskState;
import com.termux.ui.wine.runtime.RootfsSetupRecipe;

import org.json.JSONException;
import org.json.JSONObject;

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
        RuntimeEditingStop.requireNotCancelled(getFilesDir(), taskId);
        if (tasks.find(taskId).isPresent()) return;
        if (com.termux.ui.wine.domain.GameContainer.DEFAULT_ID.equals(containerId)) {
            throw new IOException("rootfs_container_must_be_independent");
        }
        RuntimeInstallationGate.requireRootfsSlot(getFilesDir(), taskId);
        RootfsSetupRecipe recipe = RootfsSetupRecipe.require(packageName);
        if (!baseOnly) {
            GameContainer selection = containers.find(containerId)
                .orElseThrow(() -> new IOException("rootfs_container_missing"));
            new FileGameContainerRepository(selectionDirectory(taskId)).save(selection);
        }
        // The all-zero digest denotes dependency preparation only, never a runnable recipe.
        RuntimeSetupTask task = RuntimeSetupTask.queued(taskId, packageName,
            recipe.getVersion(), PENDING_RECIPE, recipe.getSourceComponentId(),
            containerId, containerId, baseOnly, System.currentTimeMillis());
        saveTask(task);
        prepareAndStart(task);
    }

    private static final String PENDING_RECIPE = new String(new char[64]).replace('\0', '0');

    private File selectionDirectory(String taskId) {
        return new File(paths.getRuntimeSetupDirectory(), "selections/" + taskId);
    }

    private GameContainer selectedContainer(RuntimeSetupTask task) throws IOException {
        return new FileGameContainerRepository(selectionDirectory(task.getTaskId()))
            .find(task.getContainerId()).orElseThrow(() -> new IOException("rootfs_preparation_selection_missing"));
    }

    private com.termux.ui.wine.domain.RuntimeProfile selectedProfile(RuntimeSetupTask task) throws IOException {
        GameContainer c = selectedContainer(task);
        return new com.termux.ui.wine.domain.RuntimeProfile(c.getId(), c.getWinePackage(),
            c.getGraphicsDriver(), c.getDxWrapper(), c.getAudioDriver(), c.getResolution(),
            c.getBox64Preset(), c.getEnvironment(), "xinput",
            com.termux.ui.domain.LaunchExecutionMode.APP_SHELL,
            java.util.Collections.emptyMap(), c.getBackendType(), c.getRootfsPackage(), c.getId());
    }

    private com.termux.ui.wine.data.ProotBindingStore taskBindings() {
        return new com.termux.ui.wine.data.ProotBindingStore(new File(paths.getRuntimeSetupDirectory(), "bindings"));
    }

    private void prepareAndStart(RuntimeSetupTask task) throws Exception {
        RuntimeEditingStop.requireNotCancelled(getFilesDir(), task.getTaskId());
        RootfsSetupRecipe recipe = RootfsSetupRecipe.require(task.getPackageName(), task.getVersion());
        if (recipe.getVersion() != RootfsSetupRecipe.EXTERNAL_COMPONENTS_VERSION) {
            throw new IOException("rootfs_old_preparation_unsupported");
        }
        java.util.Set<String> required = new java.util.LinkedHashSet<>(recipe.getBaseComponentIds());
        if (!task.isBaseOnly()) required.addAll(new com.termux.ui.wine.runtime.ProotDependencyResolver()
            .requiredComponentIds(selectedProfile(task), paths));
        if (!task.isBaseOnly()) {
            String prefix = PrebuiltPrefixAssets.componentId(selectedProfile(task).getWinePackage());
            if (prefix != null) required.add(prefix);
        }
        if (PENDING_RECIPE.equals(task.getRecipeSha256())) {
            new RootfsRuntimeComponentPreparer(this, task.getTaskId()).ensure(required);
        }
        RuntimeEditingStop.requireNotCancelled(getFilesDir(), task.getTaskId());
        RuntimeInstallationGate.requireRootfsSlot(getFilesDir(), task.getTaskId());
        java.util.LinkedHashMap<String, InstalledComponent> baseComponents = resolveBaseComponents(recipe);
        RootfsSetupAssetInstaller.Installed assets = new RootfsSetupAssetInstaller(this, paths)
            .install(recipe, componentSha256s(baseComponents));
        if (!task.isBaseOnly()) {
            new RootfsSetupAssetInstaller(this, paths).installPrefix(recipe,
                selectedProfile(task).getWinePackage());
        }
        if (PENDING_RECIPE.equals(task.getRecipeSha256())) {
            task = RuntimeSetupTask.queued(task.getTaskId(), task.getPackageName(), task.getVersion(),
                assets.getRecipeSha256(), task.getSourceComponentId(), task.getContainerId(),
                task.getContainerName(), task.isBaseOnly(), task.getCreatedAt());
            if (!task.isBaseOnly()) {
                com.termux.ui.wine.domain.RuntimeProfile p = selectedProfile(task);
                taskBindings().write(task.getTaskId(), new com.termux.ui.wine.domain.ProotBindingSnapshot(
                    p.getWinePackage(), p.getDxWrapper(),
                    com.termux.ui.wine.runtime.GameContainerProfileResolver.resolveTranslator(p.getWinePackage(), paths),
                    p.getEnvironment().getOrDefault("GAMES_CUSTOM_BOX64_ID", ""), assets.getRecipeSha256(),
                    new com.termux.ui.wine.runtime.ProotBindingResolver(new ComponentStoragePaths(getFilesDir())
                        .getInstallDirectory()).freeze(p, paths)));
            }
            // Publish the replayable task identity only after its frozen dependency record.
            saveTask(task);
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
            String runtimeTranslator = "";
            String box64ComponentId = "";
            LaunchScriptInstaller scripts = new LaunchScriptInstaller(this, paths);
            // Co-located sibling script setup_rootfs_runtime.sh sources by deriving its path
            // from PREFIX_WARMUP_SCRIPT's own directory -- see that script's COMMON_SCRIPT line.
            scripts.installRootfsScriptCommon();
            File prefixWarmupScript = scripts.installRootfsPrefixWarmup();
            if (!task.isBaseOnly()) {
                GameContainer container = selectedContainer(task);
                winePackage = container.getWinePackage();
                runtimeTranslator = container.getTranslator().getStorageValue();
                box64ComponentId = container.getEnvironment().getOrDefault("GAMES_CUSTOM_BOX64_ID", "");
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
                winePackage, winePrefixDirectory, homeDirectory, prefixWarmupScript,
                runtimeTranslator, box64ComponentId, task.isBaseOnly() ? java.util.Collections.emptyList()
                    : taskBindings().read(task.getTaskId()).getBindings());
            new SetupPreparationLog(getFilesDir(), task.getTaskId()).append("Starting RootFS setup script.");
            com.termux.ui.wine.api.WineCDriveBackupTasks.requireAvailable(getFilesDir());
            synchronized (RuntimeEditingStop.LOCK) {
                RuntimeEditingStop.requireNotCancelled(getFilesDir(), task.getTaskId());
            host.startRuntimeSetup(new RuntimeSetupRequest(task.getTaskId(),
                assets.script.getCanonicalPath(), spec.getCanonicalPath(),
                paths.getRuntimeSetupDirectory().getCanonicalPath(),
                SetupPreparationLog.file(getFilesDir(), task.getTaskId()).getCanonicalPath()));
            }
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
        RuntimeEditingStop.requireNotCancelled(getFilesDir(), task.getTaskId());
        SetupEvent event = readLastEvent(eventFile(task.getTaskId()));
        // The script's own emitted SUCCEEDED event is the only completion signal -- there is no
        // more per-container "activation" to cross-check against now that every container mounts
        // the one shared, always-current RootFS (see GameStoragePaths.getSharedRootfsDirectory()).
        if (event.state == Event.SUCCEEDED) {
            RuntimeSetupTask current = requiredTask(task.getTaskId());
            if (!current.getState().isTerminal()) {
                if (!current.isBaseOnly()) {
                    com.termux.ui.wine.domain.ProotBindingSnapshot snapshot = taskBindings().read(current.getTaskId());
                    new com.termux.ui.wine.runtime.ProotBindingResolver(new ComponentStoragePaths(getFilesDir())
                        .getInstallDirectory()).validate(snapshot.getBindings());
                    com.termux.ui.wine.runtime.ProotPreparedRuntime.configurations(paths)
                        .write(current.getContainerId(), snapshot);
                }
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

    private void saveTask(RuntimeSetupTask task) throws IOException {
        synchronized (RuntimeEditingStop.LOCK) {
            RuntimeEditingStop.requireNotCancelled(getFilesDir(), task.getTaskId());
            RuntimeSetupTask current = tasks.find(task.getTaskId()).orElse(null);
            if (current != null && current.getState().isTerminal()) throw new IOException("runtime_task_terminal");
            tasks.save(task);
        }
    }

    private RuntimeSetupTask transition(RuntimeSetupTask task,
                                            RuntimeSetupTaskState state,
                                            String error) throws IOException {
        RuntimeSetupTask updated = task.transition(state, error, System.currentTimeMillis());
        saveTask(updated);
        publish(updated);
        return updated;
    }

    private void fail(String taskId, String code) {
        if (taskId == null) return;
        try {
            SetupPreparationLog.failed(getFilesDir(), taskId, code);
        } catch (IOException error) {
            Log.e(TAG, "Unable to write setup preparation failure", error);
        }
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
