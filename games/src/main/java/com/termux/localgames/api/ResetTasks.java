package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.localgames.domain.ResetTarget;
import com.termux.localgames.service.ResetRuntimeForegroundService;

import java.util.UUID;

/** Public command surface for purging one runtime environment back to not-installed. */
public final class ResetTasks {
    public static final String ACTION_ENQUEUE = "com.termux.localgames.action.ENQUEUE_RESET";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.localgames.action.RECONCILE_ALL_RESET";
    public static final String EXTRA_TASK_ID = "com.termux.localgames.extra.RESET_TASK_ID";
    public static final String EXTRA_TARGET = "com.termux.localgames.extra.RESET_TARGET";
    public static final String EXTRA_CONTAINER_ID = "com.termux.localgames.extra.RESET_CONTAINER_ID";

    private ResetTasks() {}

    @NonNull
    public static String enqueue(@NonNull Context context, @NonNull ResetTarget target,
                                @Nullable String containerId) {
        String taskId = "reset-" + UUID.randomUUID().toString();
        Intent intent = new Intent(context, ResetRuntimeForegroundService.class)
            .setAction(ACTION_ENQUEUE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_TARGET, target.name());
        if (containerId != null) intent.putExtra(EXTRA_CONTAINER_ID, containerId);
        start(context, intent);
        return taskId;
    }

    public static void reconcileAll(@NonNull Context context) {
        start(context, new Intent(context, ResetRuntimeForegroundService.class)
            .setAction(ACTION_RECONCILE_ALL));
    }

    private static void start(Context context, Intent intent) {
        Context application = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) application.startForegroundService(intent);
        else application.startService(intent);
    }
}
