package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.localgames.service.RootfsSetupForegroundService;

import java.util.UUID;

/** Public command surface for persistent on-device runtime builds. */
public final class RuntimeSetupTasks {
    public static final String ACTION_ENQUEUE = "com.termux.localgames.action.ENQUEUE_RUNTIME_SETUP";
    public static final String ACTION_RECONCILE = "com.termux.localgames.action.RECONCILE_RUNTIME_SETUP";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.localgames.action.RECONCILE_ALL_RUNTIME_SETUP";
    public static final String ACTION_ROLLBACK = "com.termux.localgames.action.ROLLBACK_RUNTIME";
    public static final String EXTRA_TASK_ID = "com.termux.localgames.extra.SETUP_TASK_ID";
    public static final String EXTRA_PACKAGE_NAME = "com.termux.localgames.extra.ROOTFS_PACKAGE";
    public static final String EXTRA_CONTAINER_ID = "com.termux.localgames.extra.ROOTFS_CONTAINER_ID";

    private RuntimeSetupTasks() {}

    @NonNull
    public static String enqueue(@NonNull Context context, @NonNull String packageName,
                                 @NonNull String containerId) {
        requireId(packageName, "packageName");
        requireId(containerId, "containerId");
        String taskId = "setup-" + UUID.randomUUID().toString();
        Intent intent = new Intent(context, RootfsSetupForegroundService.class)
            .setAction(ACTION_ENQUEUE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_PACKAGE_NAME, packageName)
            .putExtra(EXTRA_CONTAINER_ID, containerId);
        start(context, intent);
        return taskId;
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

    public static void rollback(@NonNull Context context, @NonNull String packageName,
                                @NonNull String containerId) {
        requireId(packageName, "packageName");
        requireId(containerId, "containerId");
        start(context, new Intent(context, RootfsSetupForegroundService.class)
            .setAction(ACTION_ROLLBACK).putExtra(EXTRA_PACKAGE_NAME, packageName)
            .putExtra(EXTRA_CONTAINER_ID, containerId));
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
