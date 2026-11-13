package com.termux.ui.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.ResultReceiver;

import androidx.annotation.NonNull;

import com.termux.ui.service.ComponentTaskForegroundService;

import java.util.UUID;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Public command API for durable component delivery tasks. */
public final class ComponentTasks {

    public static final String ACTION_INSTALL = "com.termux.ui.action.INSTALL_COMPONENT";
    public static final String ACTION_UNINSTALL = "com.termux.ui.action.UNINSTALL_COMPONENT";
    public static final String ACTION_REINSTALL = "com.termux.ui.action.REINSTALL_COMPONENT";

    public static String install(Context context, String componentId, boolean reinstall) {
        requireIdentifier(componentId, "componentId");
        String taskId = UUID.randomUUID().toString();
        start(context, command(context, reinstall ? ACTION_REINSTALL : ACTION_INSTALL, taskId)
            .putExtra(EXTRA_COMPONENT_ID, componentId));
        return taskId;
    }

    public static void uninstall(Context context, String componentId, ResultReceiver receiver) {
        requireIdentifier(componentId, "componentId");
        start(context, command(context, ACTION_UNINSTALL, UUID.randomUUID().toString())
            .putExtra(EXTRA_COMPONENT_ID, componentId).putExtra(EXTRA_COMMAND_RECEIVER, receiver));
    }

    public static final String ACTION_ENQUEUE = "com.termux.ui.action.ENQUEUE_COMPONENT";
    public static final String ACTION_PAUSE = "com.termux.ui.action.PAUSE_COMPONENT";
    public static final String ACTION_RESUME = "com.termux.ui.action.RESUME_COMPONENT";
    public static final String ACTION_RETRY = "com.termux.ui.action.RETRY_COMPONENT";
    public static final String ACTION_CANCEL = "com.termux.ui.action.CANCEL_COMPONENT";
    public static final String ACTION_ROLLBACK = "com.termux.ui.action.ROLLBACK_COMPONENT";
    public static final String ACTION_ACTIVATE = "com.termux.ui.action.ACTIVATE_COMPONENT";
    public static final String ACTION_RECONCILE =
        "com.termux.ui.action.RECONCILE_COMPONENTS";

    public static final String EXTRA_TASK_ID = "com.termux.ui.extra.TASK_ID";
    public static final String EXTRA_COMPONENT_ID = "com.termux.ui.extra.COMPONENT_ID";
    public static final String EXTRA_COMMAND_RECEIVER = "com.termux.ui.extra.COMPONENT_COMMAND_RECEIVER";
    public static final String EXTRA_COMMAND_ERROR = "com.termux.ui.extra.COMPONENT_COMMAND_ERROR";

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

    /** The parent is persisted before this exact child is admitted by the installation gate. */
    public static String enqueueRuntimeDependency(Context context, String componentId, String parent) throws IOException {
        requireIdentifier(componentId, "componentId");
        String taskId = UUID.randomUUID().toString();
        com.termux.ui.service.RuntimeInstallationGate.registerDependency(context.getFilesDir(), parent, taskId);
        start(context, command(context, ACTION_ENQUEUE, taskId).putExtra(EXTRA_COMPONENT_ID, componentId));
        return taskId;
    }

    public static void activateRuntimeDependency(Context context, String componentId, String parent) throws IOException {
        requireIdentifier(componentId, "componentId");
        String taskId = UUID.randomUUID().toString();
        com.termux.ui.service.RuntimeInstallationGate.registerDependency(context.getFilesDir(), parent, taskId);
        start(context, command(context, ACTION_ACTIVATE, taskId).putExtra(EXTRA_COMPONENT_ID, componentId));
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

    /** Worker-only handshake: old PAUSED/FAILED state is valid until this command is accepted. */
    public static void resumeAndAwaitAcceptance(Context context, String taskId, boolean retry,
                                                long timeoutMs) throws IOException {
        CountDownLatch accepted = new CountDownLatch(1);
        AtomicReference<String> rejected = new AtomicReference<>();
        ResultReceiver receiver = new ResultReceiver(null) {
            @Override protected void onReceiveResult(int resultCode, Bundle data) {
                if (resultCode != 0) rejected.set(data == null ? "component_command_rejected"
                    : data.getString(EXTRA_COMMAND_ERROR, "component_command_rejected"));
                accepted.countDown();
            }
        };
        start(context, command(context, retry ? ACTION_RETRY : ACTION_RESUME, taskId)
            .putExtra(EXTRA_COMMAND_RECEIVER, receiver));
        try {
            if (!accepted.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("component_command_timeout:" + taskId);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("component_prepare_interrupted", error);
        }
        if (rejected.get() != null) throw new IOException(rejected.get());
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
