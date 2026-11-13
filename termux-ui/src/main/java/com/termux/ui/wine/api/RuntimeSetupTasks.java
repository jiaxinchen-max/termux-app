package com.termux.ui.wine.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.domain.RuntimeSetupTask;
import com.termux.ui.wine.service.RootfsSetupForegroundService;

import java.io.IOException;
import java.util.UUID;

/** Public command surface for persistent on-device runtime builds. */
public final class RuntimeSetupTasks {
    public static final String ACTION_ENQUEUE = "com.termux.ui.action.ENQUEUE_RUNTIME_SETUP";
    public static final String ACTION_RECONCILE = "com.termux.ui.action.RECONCILE_RUNTIME_SETUP";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.ui.action.RECONCILE_ALL_RUNTIME_SETUP";
    public static final String EXTRA_TASK_ID = "com.termux.ui.extra.SETUP_TASK_ID";
    public static final String EXTRA_PACKAGE_NAME = "com.termux.ui.extra.ROOTFS_PACKAGE";
    public static final String EXTRA_CONTAINER_ID = "com.termux.ui.extra.ROOTFS_CONTAINER_ID";
    public static final String EXTRA_BASE_ONLY = "com.termux.ui.extra.ROOTFS_BASE_ONLY";

    private RuntimeSetupTasks() {}

    @NonNull
    public static String enqueue(@NonNull Context context, @NonNull String packageName,
                                 @NonNull String containerId) {
        return enqueue(context, packageName, containerId, false);
    }

    /** A BASE_ONLY build (re)builds the shared RootFS base archive and never creates or
     *  activates a real game container -- see setup_rootfs_runtime.sh's BASE_ONLY spec flag.
     *  {@code containerId} must still be a disposable, non-DEFAULT_ID identifier (the setup
     *  foreground service requires one), but no container is ever created under it. */
    @NonNull
    public static String enqueueBaseOnly(@NonNull Context context, @NonNull String packageName,
                                        @NonNull String containerId) {
        return enqueue(context, packageName, containerId, true);
    }

    @NonNull
    private static String enqueue(@NonNull Context context, @NonNull String packageName,
                                  @NonNull String containerId, boolean baseOnly) {
        requireId(packageName, "packageName");
        requireId(containerId, "containerId");
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        FileRuntimeSetupTaskRepository repository = new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory());
        try {
            RuntimeSetupTask task = com.termux.ui.service.SetupTaskSubmission.submit(
                com.termux.ui.wine.runtime.RuntimeEditingStop.LOCK,
                () -> {
                    for (RuntimeSetupTask current : repository.list())
                        if (!current.getState().isTerminal() && (current.getContainerId().equals(containerId)
                            || (baseOnly && current.isBaseOnly() && current.getPackageName().equals(packageName)))) return current;
                    return null;
                },
                () -> {
                    String id = "setup-" + UUID.randomUUID();
                    com.termux.ui.wine.runtime.RootfsSetupRecipe recipe = com.termux.ui.wine.runtime.RootfsSetupRecipe.require(packageName);
                    if (!baseOnly) {
                        com.termux.ui.wine.domain.GameContainer container = new com.termux.ui.wine.data.FileGameContainerRepository(
                            paths.getContainersDirectory()).find(containerId).orElseThrow(() -> new IOException("rootfs_container_missing"));
                        new com.termux.ui.wine.data.FileGameContainerRepository(new java.io.File(
                            paths.getRuntimeSetupDirectory(), "selections/" + id)).save(container);
                    }
                    return RuntimeSetupTask.queued(id, packageName, recipe.getVersion(),
                        new String(new char[64]).replace('\0', '0'), recipe.getSourceComponentId(),
                        containerId, containerId, baseOnly, System.currentTimeMillis());
                }, repository::save,
                queued -> {
                    if (!baseOnly) com.termux.ui.wine.runtime.RuntimeEditingStop.register(queued.getTaskId(), "container:" + containerId);
                    start(context, new Intent(context, RootfsSetupForegroundService.class).setAction(ACTION_ENQUEUE)
                        .putExtra(EXTRA_TASK_ID, queued.getTaskId()).putExtra(EXTRA_PACKAGE_NAME, packageName)
                        .putExtra(EXTRA_CONTAINER_ID, containerId).putExtra(EXTRA_BASE_ONLY, baseOnly));
                }, queued -> repository.save(queued.transition(com.termux.ui.wine.domain.RuntimeSetupTaskState.FAILED,
                    "setup_dispatch_failed", System.currentTimeMillis())));
            return task.getTaskId();
        } catch (IOException error) {
            throw new IllegalStateException("rootfs_setup_submit_failed", error);
        }
    }

    public static void reconcile(@NonNull Context context, @NonNull String taskId) {
        requireId(taskId, "taskId");
        start(context, new Intent(context, RootfsSetupForegroundService.class)
            .setAction(ACTION_RECONCILE).putExtra(EXTRA_TASK_ID, taskId));
    }

    public static void reconcileAll(@NonNull Context context) {
        start(context, createReconcileAllIntent(context));
    }

    @NonNull
    public static Intent createReconcileAllIntent(@NonNull Context context) {
        return new Intent(context, RootfsSetupForegroundService.class)
            .setAction(ACTION_RECONCILE_ALL);
    }

    private static void start(Context context, Intent intent) {
        Context application = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) application.startForegroundService(intent);
        else application.startService(intent);
    }

    private static void requireId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
    }
}
