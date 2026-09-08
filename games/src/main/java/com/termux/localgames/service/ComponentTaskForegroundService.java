package com.termux.localgames.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.termux.localgames.R;
import com.termux.localgames.activity.LocalGamesActivity;
import com.termux.localgames.api.ComponentTasks;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.api.RuntimeComponentActivation;
import com.termux.localgames.components.ComponentDownloader;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.components.DefaultHttpConnectionFactory;
import com.termux.localgames.components.delivery.ComponentDeliveryCoordinator;
import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.components.install.ComponentInstaller;
import com.termux.localgames.components.install.ComponentInstallException;
import com.termux.localgames.components.install.InstalledComponent;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.data.ComponentTaskRepository;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Foreground owner for persistent component download, verification and installation work. */
public final class ComponentTaskForegroundService extends Service {

    private static final String TAG = "GamesComponentTask";
    private static final String CHANNEL_ID = "games_component_delivery";
    private static final int NOTIFICATION_ID = 23091;
    private static final String INDEX_ASSET = "termux-box-packages/index-v1.json";
    /**
     * Runtime packages are built for the Termux GLIBC prefix and ship symbolic links that point
     * there absolutely; those links only resolve once the package is activated into that prefix.
     * Targets outside this root stay rejected.
     */
    private static final String RUNTIME_LINK_ROOT = "/data/data/com.termux/files/usr/";

    private final AtomicInteger pendingCommands = new AtomicInteger();
    private final Set<String> runningTasks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final Map<String, String> consoleSnapshots = new ConcurrentHashMap<>();
    private final Object controlRegistrationLock = new Object();

    private ExecutorService executor;
    private Handler mainHandler;
    private volatile ComponentDeliveryCoordinator coordinator;
    private ComponentIndex componentIndex;
    private volatile int latestStartId;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "GamesComponentDelivery");
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        });
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildPreparingNotification());
        submit(this::recoverTasks);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        latestStartId = startId;
        if (intent == null || intent.getAction() == null) return START_STICKY;

        String action = intent.getAction();
        String taskId = intent.getStringExtra(ComponentTasks.EXTRA_TASK_ID);
        String componentId = intent.getStringExtra(ComponentTasks.EXTRA_COMPONENT_ID);
        if (ComponentTasks.ACTION_PAUSE.equals(action) && taskId != null) {
            synchronized (controlRegistrationLock) {
                ComponentDeliveryCoordinator current = coordinator;
                if (current != null && runningTasks.contains(taskId)) {
                    current.signalPause(taskId);
                }
            }
        } else if (ComponentTasks.ACTION_CANCEL.equals(action) && taskId != null) {
            synchronized (controlRegistrationLock) {
                ComponentDeliveryCoordinator current = coordinator;
                if (current != null && runningTasks.contains(taskId)) {
                    current.signalCancel(taskId);
                }
            }
        }
        submit(() -> handleCommand(action, taskId, componentId));
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        ComponentDeliveryCoordinator current = coordinator;
        if (current != null) current.pauseAllRunning();
        if (executor != null) executor.shutdownNow();
        super.onDestroy();
    }

    private void handleCommand(String action, String taskId, String componentId) {
        try {
            ComponentDeliveryCoordinator current = requireCoordinator();
            if (ComponentTasks.ACTION_ENQUEUE.equals(action)) {
                RuntimeInstallationGate.requireComponentSlot(getFilesDir(), null);
                ComponentDescriptor descriptor = requiredDescriptor(componentId);
                current.enqueue(descriptor, requiredIdentifier(taskId, "taskId"));
                ComponentTaskConsoleLog.append(getFilesDir(), taskId,
                    "Queued component " + descriptor.getDisplayName());
                ComponentTaskConsoleLog.append(getFilesDir(), taskId,
                    "GET " + descriptor.getUrl() + " · expected " + descriptor.getSize() +
                        " bytes · sha256 " + descriptor.getSha256());
                executeTask(taskId);
            } else if (ComponentTasks.ACTION_PAUSE.equals(action)) {
                current.requestPause(requiredIdentifier(taskId, "taskId"), this::publishTask);
            } else if (ComponentTasks.ACTION_CANCEL.equals(action)) {
                current.requestCancel(requiredIdentifier(taskId, "taskId"), this::publishTask);
            } else if (ComponentTasks.ACTION_RESUME.equals(action) ||
                ComponentTasks.ACTION_RETRY.equals(action)) {
                String requiredTaskId = requiredIdentifier(taskId, "taskId");
                RuntimeInstallationGate.requireComponentSlot(getFilesDir(), requiredTaskId);
                current.prepareResume(requiredTaskId, this::publishTask);
                ComponentTaskConsoleLog.append(getFilesDir(), requiredTaskId,
                    ComponentTasks.ACTION_RETRY.equals(action) ? "Retry requested" : "Resume requested");
                executeTask(requiredTaskId);
            } else if (ComponentTasks.ACTION_ROLLBACK.equals(action)) {
                RuntimeInstallationGate.requireComponentSlot(getFilesDir(), null);
                InstalledComponent component = current.rollback(
                    requiredIdentifier(componentId, "componentId"));
                publishMessage(getString(R.string.local_games_component_rollback_complete,
                    component.getPackageName(), component.getVersion()));
            } else if (ComponentTasks.ACTION_ACTIVATE.equals(action)) {
                RuntimeInstallationGate.requireComponentSlot(getFilesDir(), null);
                activateInstalledComponent(requiredIdentifier(componentId, "componentId"));
            } else if (ComponentTasks.ACTION_RECONCILE.equals(action)) {
                recoverTasks();
            } else {
                throw new IOException("Unsupported component task command");
            }
        } catch (Exception error) {
            publishFailure(error);
        }
    }

    private void recoverTasks() {
        try {
            ComponentDeliveryCoordinator current = requireCoordinator();
            for (String taskId : current.recoverableTaskIds()) executeTask(taskId);
        } catch (Exception error) {
            publishFailure(error);
        }
    }

    private void executeTask(String taskId) {
        ComponentDeliveryCoordinator current;
        synchronized (controlRegistrationLock) {
            if (!runningTasks.add(taskId)) return;
            try {
                current = requireCoordinator();
                RuntimeInstallationGate.requireComponentSlot(getFilesDir(), taskId);
                current.prepareExecution(taskId);
            } catch (IOException error) {
                runningTasks.remove(taskId);
                ComponentTaskConsoleLog.append(getFilesDir(), taskId,
                    "Task blocked: " + error.getMessage());
                publishFailure(error);
                return;
            } catch (Exception error) {
                runningTasks.remove(taskId);
                ComponentTaskConsoleLog.append(getFilesDir(), taskId,
                    "Task blocked: " + error.getMessage());
                publishFailure(error);
                return;
            }
        }
        try {
            ComponentTaskConsoleLog.append(getFilesDir(), taskId,
                "Starting download, verification, extraction and activation pipeline.");
            current.execute(taskId, this::publishTask);
        } catch (Exception error) {
            ComponentTaskConsoleLog.append(getFilesDir(), taskId,
                "Task failed: " + stableMessage(error));
            publishExecutionFailure(current, taskId, error);
        } finally {
            synchronized (controlRegistrationLock) {
                runningTasks.remove(taskId);
            }
        }
    }

    private synchronized ComponentDeliveryCoordinator requireCoordinator() throws IOException {
        if (coordinator != null) return coordinator;
        File retainedDownloads = retainedDownloadDirectory();
        ComponentStoragePaths privatePaths = new ComponentStoragePaths(getFilesDir());
        migrateVerifiedArchives(privatePaths.getDownloadsDirectory(), retainedDownloads);
        ComponentStoragePaths paths = new ComponentStoragePaths(getFilesDir(), retainedDownloads);
        ComponentTaskRepository repository = new FileComponentTaskRepository(
            paths.getTasksDirectory());
        ComponentDownloader downloader = new ComponentDownloader(repository,
            paths.getDownloadsDirectory(),
            new DefaultHttpConnectionFactory());
        ComponentInstaller installer = new ComponentInstaller(repository,
            paths.getInstallDirectory(),
            new AndroidArchiveFileOperations(),
            Collections.singletonList(RUNTIME_LINK_ROOT));
        try (InputStream input = getAssets().open(INDEX_ASSET)) {
            componentIndex = new ComponentIndexParser().parse(input);
        }
        installer.setRuntimeComponentActivator(this::activateRuntimeComponent);
        coordinator = new ComponentDeliveryCoordinator(repository, downloader, installer,
            paths.getDownloadsDirectory());
        return coordinator;
    }

    private File retainedDownloadDirectory() throws IOException {
        File downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS);
        File directory = new File(downloads, "Termux/Games/components");
        if ((directory.isDirectory() || directory.mkdirs()) && directory.canWrite()) {
            return directory;
        }
        File scoped = getExternalFilesDir("games/components");
        if (scoped != null && (scoped.isDirectory() || scoped.mkdirs()) && scoped.canWrite()) {
            return scoped;
        }
        throw new IOException("component_external_cache_unavailable");
    }

    /** Copies old private verified archives once; sources are retained as a rollback cache. */
    private static void migrateVerifiedArchives(File source, File destination) throws IOException {
        if (source.equals(destination) || !source.isDirectory()) return;
        File[] archives = source.listFiles(file -> file.isFile() &&
            (file.getName().endsWith(".archive") || file.getName().endsWith(".part")));
        if (archives == null) throw new IOException("component_cache_list_failed");
        byte[] buffer = new byte[64 * 1024];
        for (File archive : archives) {
            File target = new File(destination, archive.getName());
            if (target.isFile() && target.length() == archive.length()) continue;
            File temporary = new File(destination, archive.getName() + ".migrating");
            try (FileInputStream input = new FileInputStream(archive);
                 FileOutputStream output = new FileOutputStream(temporary, false)) {
                int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
                output.getFD().sync();
            }
            if (target.exists() && !target.delete()) {
                temporary.delete();
                throw new IOException("component_cache_replace_failed");
            }
            if (!temporary.renameTo(target)) {
                temporary.delete();
                throw new IOException("component_cache_publish_failed");
            }
        }
    }

    /**
     * Publishes a GLIBC runtime component into the host prefix the launch scripts read.
     * Components for other backends stay private to this module and are skipped.
     */
    private void activateRuntimeComponent(ComponentTask task, File preparedDirectory)
        throws IOException {
        ComponentDescriptor descriptor = requiredDescriptor(task.getPackageName());
        activateRuntimeComponent(descriptor, preparedDirectory);
    }

    private void activateInstalledComponent(String componentId) throws IOException {
        ComponentDescriptor descriptor = requiredDescriptor(componentId);
        ComponentStoragePaths paths = new ComponentStoragePaths(getFilesDir());
        InstalledComponent active = new ComponentInstallationReader(paths.getInstallDirectory())
            .read(componentId).getActive().orElseThrow(
                () -> new ComponentInstallException("runtime_activation_source_missing",
                    "No verified local component is available for activation"));
        if (active.getVersion() != descriptor.getVersion() ||
            !active.getSha256().equals(descriptor.getSha256())) {
            throw new ComponentInstallException("runtime_activation_source_mismatch",
                "Verified local component does not match the current catalog");
        }
        activateRuntimeComponent(descriptor, active.getDirectory());
        publishMessage(getString(R.string.local_games_component_activation_complete,
            descriptor.getDisplayName()));
    }

    private void activateRuntimeComponent(ComponentDescriptor descriptor,
                                          File preparedDirectory) throws IOException {
        if (!descriptor.supportsBackend(GameRuntimeBackendType.GLIBC_TERMUX_BOX)) return;
        LocalGamesHost host = LocalGames.requireHost(this);
        boolean activated = host.activateRuntimeComponent(new RuntimeComponentActivation(
            descriptor.getId(), descriptor.getVersion(), descriptor.getCategory(),
            descriptor.getUrl(), descriptor.getSize(), descriptor.getSha256(),
            preparedDirectory));
        if (!activated) {
            throw new ComponentInstallException("runtime_activation_failed",
                "Component was not published into the launcher runtime");
        }
    }

    private ComponentDescriptor requiredDescriptor(String componentId) throws IOException {
        String id = requiredIdentifier(componentId, "componentId");
        return componentIndex.find(id).orElseThrow(
            () -> new IOException("Component is absent from bundled index: " + id));
    }

    private void submit(WorkerCommand command) {
        pendingCommands.incrementAndGet();
        executor.execute(() -> {
            try {
                command.run();
            } finally {
                if (pendingCommands.decrementAndGet() == 0 && runningTasks.isEmpty()) {
                    mainHandler.post(this::stopIfIdle);
                }
            }
        });
    }

    private void stopIfIdle() {
        if (pendingCommands.get() != 0 || !runningTasks.isEmpty()) return;
        stopForeground(false);
        stopSelfResult(latestStartId);
    }

    private void publishTask(ComponentTask task) {
        String line = ComponentTaskConsoleLog.stateLine(task);
        if (!line.equals(consoleSnapshots.put(task.getTaskId(), line))) {
            ComponentTaskConsoleLog.append(getFilesDir(), task.getTaskId(), line);
        }
        NotificationManager manager = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, buildTaskNotification(task));
    }

    private void publishMessage(String message) {
        NotificationManager manager = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, baseBuilder()
            .setContentText(message)
            .setProgress(0, 0, false)
            .setOngoing(false)
            .build());
    }

    private void publishFailure(Exception error) {
        Log.e(TAG, "Component delivery command failed", error);
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        publishMessage(getString(R.string.local_games_component_task_failed, message));
    }

    private static String stableMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
            ? error.getClass().getSimpleName() : message;
    }

    private void publishExecutionFailure(ComponentDeliveryCoordinator current, String taskId,
                                         Exception error) {
        Log.e(TAG, "Component execution failed: " + taskId, error);
        try {
            ComponentTask task = current.find(taskId).orElse(null);
            if (task != null) {
                publishTask(task);
                return;
            }
        } catch (IOException persistenceError) {
            error.addSuppressed(persistenceError);
        }
        publishFailure(error);
    }

    private Notification buildPreparingNotification() {
        return baseBuilder()
            .setContentText(getString(R.string.local_games_component_preparing))
            .setProgress(0, 0, true)
            .build();
    }

    private Notification buildTaskNotification(ComponentTask task) {
        Notification.Builder builder = baseBuilder()
            .setContentText(getString(R.string.local_games_component_task_status,
                task.getPackageName(), stateLabel(task.getState())));
        if (task.getState() == ComponentTaskState.DOWNLOADING) {
            int progress = (int) Math.min(100,
                task.getDownloadedBytes() * 100L / task.getExpectedSize());
            builder.setProgress(100, progress, false)
                .addAction(commandAction(R.string.local_games_component_pause,
                    ComponentTasks.ACTION_PAUSE, task.getTaskId()))
                .addAction(commandAction(R.string.local_games_component_cancel,
                    ComponentTasks.ACTION_CANCEL, task.getTaskId()));
        } else if (task.getState() == ComponentTaskState.PAUSED ||
            task.getState() == ComponentTaskState.FAILED) {
            builder.setProgress(0, 0, false)
                .addAction(commandAction(R.string.local_games_component_resume,
                    task.getState() == ComponentTaskState.FAILED
                        ? ComponentTasks.ACTION_RETRY : ComponentTasks.ACTION_RESUME,
                    task.getTaskId()))
                .addAction(commandAction(R.string.local_games_component_cancel,
                    ComponentTasks.ACTION_CANCEL, task.getTaskId()));
        } else {
            boolean active = task.getState() == ComponentTaskState.QUEUED ||
                task.getState() == ComponentTaskState.VERIFYING ||
                task.getState() == ComponentTaskState.VERIFIED ||
                task.getState() == ComponentTaskState.INSTALLING;
            builder.setProgress(0, 0, active);
            if (!active) builder.setOngoing(false);
        }
        return builder.build();
    }

    private Notification.Action commandAction(int title, String action, String taskId) {
        Intent intent = ComponentTasks.createCommandIntent(this, action, taskId);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pendingIntent = PendingIntent.getService(this,
            (action + taskId).hashCode(), intent, flags);
        return new Notification.Action.Builder(R.drawable.local_games_ic_component,
            getString(title), pendingIntent).build();
    }

    private Notification.Builder baseBuilder() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Intent contentIntent = new Intent(this, LocalGamesActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        return builder
            .setSmallIcon(R.drawable.local_games_ic_component)
            .setContentTitle(getString(R.string.local_games_component_notification_title))
            .setContentIntent(PendingIntent.getActivity(this, 0, contentIntent, flags))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_PROGRESS);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
            getString(R.string.local_games_component_channel),
            NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.local_games_component_channel_description));
        NotificationManager manager = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        manager.createNotificationChannel(channel);
    }

    private String stateLabel(ComponentTaskState state) {
        switch (state) {
            case QUEUED: return getString(R.string.local_games_component_state_queued);
            case DOWNLOADING: return getString(R.string.local_games_component_state_downloading);
            case PAUSED: return getString(R.string.local_games_component_state_paused);
            case VERIFYING: return getString(R.string.local_games_component_state_verifying);
            case VERIFIED: return getString(R.string.local_games_component_state_verified);
            case INSTALLING: return getString(R.string.local_games_component_state_installing);
            case INSTALLED: return getString(R.string.local_games_component_state_installed);
            case FAILED: return getString(R.string.local_games_component_state_failed);
            case CANCELLED: return getString(R.string.local_games_component_state_cancelled);
            default: throw new IllegalArgumentException("Unknown component task state");
        }
    }

    private static String requiredIdentifier(String value, String name) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IOException("Invalid " + name);
        }
        return value;
    }

    private interface WorkerCommand {
        void run();
    }
}
