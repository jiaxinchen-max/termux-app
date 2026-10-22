package com.termux.ui.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;

import com.termux.ui.data.FileRootfsBackupTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.RootfsBackupTask;
import com.termux.ui.service.RootfsBackupForegroundService;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Public command surface for the base-environment Backup/Restore feature -- mirrors
 *  RuntimeSetupTasks's Intent-wrapping shape, but for a much simpler task family with no
 *  recipe/component resolution and no per-container prefix. */
public final class RootfsBackupTasks {
    public static final String ACTION_ENQUEUE =
        "com.termux.ui.action.ENQUEUE_ROOTFS_BACKUP";
    public static final String ACTION_RECONCILE =
        "com.termux.ui.action.RECONCILE_ROOTFS_BACKUP";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.ui.action.RECONCILE_ALL_ROOTFS_BACKUP";
    public static final String EXTRA_TASK_ID = "com.termux.ui.extra.BACKUP_TASK_ID";
    public static final String EXTRA_KIND = "com.termux.ui.extra.BACKUP_KIND";
    public static final String EXTRA_ARCHIVE = "com.termux.ui.extra.BACKUP_ARCHIVE";

    private RootfsBackupTasks() {}

    /** Backs up to the archive the user chose (inside shared storage). */
    @NonNull
    public static String enqueueBackup(@NonNull Context context, @NonNull File archive) {
        return enqueue(context, RootfsBackupTask.Kind.BACKUP, archive);
    }

    /** Restores from the archive the user chose, read in place. */
    @NonNull
    public static String enqueueRestore(@NonNull Context context, @NonNull File archive) {
        return enqueue(context, RootfsBackupTask.Kind.RESTORE, archive);
    }

    @NonNull
    private static String enqueue(@NonNull Context context, @NonNull RootfsBackupTask.Kind kind,
                                  @NonNull File archive) {
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
            .putExtra(EXTRA_KIND, kind.name())
            .putExtra(EXTRA_ARCHIVE, archive.getAbsolutePath());
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
