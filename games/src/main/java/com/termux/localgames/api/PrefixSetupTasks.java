package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.localgames.data.FilePrefixSetupTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.PrefixSetupTask;
import com.termux.localgames.service.PrefixSetupForegroundService;

import java.io.IOException;
import java.util.UUID;

/** Public command surface for persistent GLIBC Wine-prefix initialization. */
public final class PrefixSetupTasks {
    public static final String ACTION_ENQUEUE =
        "com.termux.localgames.action.ENQUEUE_PREFIX_SETUP";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.localgames.action.RECONCILE_ALL_PREFIX_SETUP";
    public static final String EXTRA_TASK_ID =
        "com.termux.localgames.extra.PREFIX_SETUP_TASK_ID";
    public static final String EXTRA_GAME_ID =
        "com.termux.localgames.extra.PREFIX_SETUP_GAME_ID";

    private PrefixSetupTasks() {}

    @NonNull
    public static String enqueue(@NonNull Context context, @NonNull String gameId) {
        requireId(gameId, "gameId");
        try {
            FilePrefixSetupTaskRepository repository =
                new FilePrefixSetupTaskRepository(new GameStoragePaths(context.getFilesDir())
                    .getPrefixSetupTasksDirectory());
            for (PrefixSetupTask task : repository.list()) {
                if (task.getGameId().equals(gameId) && !task.getState().isTerminal()) {
                    reconcileAll(context);
                    return task.getTaskId();
                }
            }
        } catch (IOException ignored) {
            // The foreground owner will persist a stable failure if storage is unavailable.
        }
        String taskId = "prefix-" + UUID.randomUUID().toString();
        start(context, new Intent(context, PrefixSetupForegroundService.class)
            .setAction(ACTION_ENQUEUE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_GAME_ID, gameId));
        return taskId;
    }

    public static void reconcileAll(@NonNull Context context) {
        start(context, new Intent(context, PrefixSetupForegroundService.class)
            .setAction(ACTION_RECONCILE_ALL));
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
