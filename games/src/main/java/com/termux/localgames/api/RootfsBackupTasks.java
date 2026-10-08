package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.localgames.data.FileRootfsBackupTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.RootfsBackupTask;
import com.termux.localgames.service.RootfsBackupForegroundService;

import java.io.IOException;
import java.util.UUID;

/** Public command surface for the base-environment Backup/Restore feature -- mirrors
 *  RuntimeSetupTasks's Intent-wrapping shape, but for a much simpler task family with no
 *  recipe/component resolution and no per-container prefix. */
public final class RootfsBackupTasks {
    public static final String ACTION_ENQUEUE =
        "com.termux.localgames.action.ENQUEUE_ROOTFS_BACKUP";
    public static final String ACTION_RECONCILE =
        "com.termux.localgames.action.RECONCILE_ROOTFS_BACKUP";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.localgames.action.RECONCILE_ALL_ROOTFS_BACKUP";
    public static final String EXTRA_TASK_ID = "com.termux.localgames.extra.BACKUP_TASK_ID";
    public static final String EXTRA_KIND = "com.termux.localgames.extra.BACKUP_KIND";

    private RootfsBackupTasks() {}

    @NonNull
    public static String enqueueBackup(@NonNull Context context) {
        return enqueue(context, RootfsBackupTask.Kind.BACKUP);
    }

    @NonNull
    public static String enqueueRestore(@NonNull Context context) {
        return enqueue(context, RootfsBackupTask.Kind.RESTORE);
    }

    @NonNull
    private static String enqueue(@NonNull Context context, @NonNull RootfsBackupTask.Kind kind) {
        try {
            FileRootfsBackupTaskRepository repository = new FileRootfsBackupTaskRepository(
                new GameStoragePaths(context.getFilesDir()).getBackupTasksDirectory());
            // At most one backup/restore in flight at a time (they share the one external backup
            // file) -- if one is already running, of either kind, hand back its id so the caller
            // just re-shows its progress instead of racing a second task against the same file.
            for (RootfsBackupTask task : repository.list()) {
                if (!task.getState().isTerminal()) {
                    reconcileAll(context);
                    return task.getTaskId();
                }
            }
        } catch (IOException ignored) {
            // The foreground owner will persist a stable failure if storage is unavailable.
        }
        String taskId = "backup-" + UUID.randomUUID().toString();
        Intent intent = new Intent(context, RootfsBackupForegroundService.class)
            .setAction(ACTION_ENQUEUE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_KIND, kind.name());
        start(context, intent);
        return taskId;
    }

    public static void reconcile(@NonNull Context context, @NonNull String taskId) {
        requireId(taskId);
        start(context, new Intent(context, RootfsBackupForegroundService.class)
            .setAction(ACTION_RECONCILE).putExtra(EXTRA_TASK_ID, taskId));
    }

    public static void reconcileAll(@NonNull Context context) {
        start(context, new Intent(context, RootfsBackupForegroundService.class)
            .setAction(ACTION_RECONCILE_ALL));
    }

    private static void start(Context context, Intent intent) {
        Context application = context.getApplicationContext();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) application.startForegroundService(intent);
        else application.startService(intent);
    }

    private static void requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid taskId");
        }
    }
}
