package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.localgames.service.ComponentTaskForegroundService;

import java.util.UUID;

/** Public command API for durable component delivery tasks. */
public final class ComponentTasks {

    public static final String ACTION_ENQUEUE = "com.termux.localgames.action.ENQUEUE_COMPONENT";
    public static final String ACTION_PAUSE = "com.termux.localgames.action.PAUSE_COMPONENT";
    public static final String ACTION_RESUME = "com.termux.localgames.action.RESUME_COMPONENT";
    public static final String ACTION_RETRY = "com.termux.localgames.action.RETRY_COMPONENT";
    public static final String ACTION_CANCEL = "com.termux.localgames.action.CANCEL_COMPONENT";
    public static final String ACTION_ROLLBACK = "com.termux.localgames.action.ROLLBACK_COMPONENT";
    public static final String ACTION_ACTIVATE = "com.termux.localgames.action.ACTIVATE_COMPONENT";
    public static final String ACTION_RECONCILE =
        "com.termux.localgames.action.RECONCILE_COMPONENTS";

    public static final String EXTRA_TASK_ID = "com.termux.localgames.extra.TASK_ID";
    public static final String EXTRA_COMPONENT_ID = "com.termux.localgames.extra.COMPONENT_ID";

    private ComponentTasks() {
    }

    /** Enqueues a component from the bundled index and returns its durable task ID. */
    @NonNull
    public static String enqueue(@NonNull Context context, @NonNull String componentId) {
        requireIdentifier(componentId, "componentId");
        String taskId = UUID.randomUUID().toString();
        start(context, command(context, ACTION_ENQUEUE, taskId)
            .putExtra(EXTRA_COMPONENT_ID, componentId));
        return taskId;
    }

    public static void pause(@NonNull Context context, @NonNull String taskId) {
        start(context, command(context, ACTION_PAUSE, taskId));
    }

    public static void resume(@NonNull Context context, @NonNull String taskId) {
        start(context, command(context, ACTION_RESUME, taskId));
    }

    public static void retry(@NonNull Context context, @NonNull String taskId) {
        start(context, command(context, ACTION_RETRY, taskId));
    }

    public static void cancel(@NonNull Context context, @NonNull String taskId) {
        start(context, command(context, ACTION_CANCEL, taskId));
    }

    public static void rollback(@NonNull Context context, @NonNull String componentId) {
        requireIdentifier(componentId, "componentId");
        Intent intent = new Intent(context, ComponentTaskForegroundService.class)
            .setAction(ACTION_ROLLBACK)
            .putExtra(EXTRA_COMPONENT_ID, componentId);
        start(context, intent);
    }

    /** Publishes an already verified local component into the launcher runtime. */
    public static void activate(@NonNull Context context, @NonNull String componentId) {
        requireIdentifier(componentId, "componentId");
        Intent intent = new Intent(context, ComponentTaskForegroundService.class)
            .setAction(ACTION_ACTIVATE)
            .putExtra(EXTRA_COMPONENT_ID, componentId);
        start(context, intent);
    }

    /** Restarts durable in-flight work after app process or package replacement. */
    public static void reconcile(@NonNull Context context) {
        start(context, createReconcileIntent(context));
    }

    @NonNull
    public static Intent createReconcileIntent(@NonNull Context context) {
        return new Intent(context, ComponentTaskForegroundService.class)
            .setAction(ACTION_RECONCILE);
    }

    @NonNull
    public static Intent createCommandIntent(@NonNull Context context, @NonNull String action,
                                             @NonNull String taskId) {
        return command(context, action, taskId);
    }

    private static Intent command(Context context, String action, String taskId) {
        requireIdentifier(taskId, "taskId");
        return new Intent(context, ComponentTaskForegroundService.class)
            .setAction(action)
            .putExtra(EXTRA_TASK_ID, taskId);
    }

    private static void start(Context context, Intent intent) {
        Context application = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent);
        } else {
            application.startService(intent);
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }
}
