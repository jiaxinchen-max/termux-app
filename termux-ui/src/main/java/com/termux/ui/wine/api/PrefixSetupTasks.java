package com.termux.ui.wine.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.service.PrefixSetupForegroundService;

import java.io.IOException;
import java.util.UUID;

/** Public command surface for persistent GLIBC Wine-prefix initialization. */
public final class PrefixSetupTasks {
    public static final String ACTION_ENQUEUE =
        "com.termux.ui.action.ENQUEUE_PREFIX_SETUP";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.ui.action.RECONCILE_ALL_PREFIX_SETUP";
    public static final String EXTRA_TASK_ID =
        "com.termux.ui.extra.PREFIX_SETUP_TASK_ID";
    public static final String EXTRA_GAME_ID =
        "com.termux.ui.extra.PREFIX_SETUP_GAME_ID";

    private PrefixSetupTasks() {}

    @NonNull
    public static String enqueue(@NonNull Context context, @NonNull String gameId) {
        requireId(gameId, "gameId");
        com.termux.ui.data.GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        FilePrefixSetupTaskRepository repository = new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory());
        try {
            PrefixSetupTask task = com.termux.ui.service.SetupTaskSubmission.submit(
                com.termux.ui.wine.runtime.RuntimeEditingStop.LOCK,
                () -> {
                    ActiveWineInstallation active = ActiveWineInstallation.find(context.getFilesDir(), gameId);
                    return active != null && !active.rootfs ? repository.find(active.taskId).orElse(null) : null;
                },
                () -> {
                    com.termux.ui.wine.domain.RuntimeProfile profile = new com.termux.ui.wine.data.FileRuntimeProfileRepository(
                        paths.getProfilesDirectory()).find(gameId).orElseThrow(() -> new IOException("runtime_profile_missing"));
                    return PrefixSetupTask.queued("prefix-" + UUID.randomUUID(), gameId,
                        profile.getWinePackage(), System.currentTimeMillis());
                }, repository::save,
                queued -> {
                    com.termux.ui.wine.runtime.RuntimeEditingStop.register(queued.getTaskId(), "game:" + gameId);
                    start(context, new Intent(context, PrefixSetupForegroundService.class)
                        .setAction(ACTION_ENQUEUE).putExtra(EXTRA_TASK_ID, queued.getTaskId())
                        .putExtra(EXTRA_GAME_ID, gameId));
                },
                queued -> repository.save(queued.transition(com.termux.ui.wine.domain.RuntimeSetupTaskState.FAILED,
                    "setup_dispatch_failed", System.currentTimeMillis())));
            return task.getTaskId();
        } catch (IOException error) {
            throw new IllegalStateException("prefix_setup_submit_failed", error);
        }
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
