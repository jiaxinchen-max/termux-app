package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.localgames.data.FileCustomComponentInstallTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.CustomComponentInstallTask;
import com.termux.localgames.domain.CustomRuntimeComponent;
import com.termux.localgames.domain.RuntimeTranslator;
import com.termux.localgames.service.CustomComponentInstallForegroundService;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Public command surface for installing a user-picked local Wine/Box64 build into the shared
 *  RootFS -- mirrors RootfsBackupTasks's Intent-wrapping shape. */
public final class CustomComponentInstallTasks {
    public static final String ACTION_ENQUEUE =
        "com.termux.localgames.action.ENQUEUE_CUSTOM_COMPONENT_INSTALL";
    public static final String ACTION_RECONCILE =
        "com.termux.localgames.action.RECONCILE_CUSTOM_COMPONENT_INSTALL";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.localgames.action.RECONCILE_ALL_CUSTOM_COMPONENT_INSTALL";
    public static final String EXTRA_TASK_ID = "com.termux.localgames.extra.CUSTOM_INSTALL_TASK_ID";
    public static final String EXTRA_COMPONENT_ID =
        "com.termux.localgames.extra.CUSTOM_INSTALL_COMPONENT_ID";
    public static final String EXTRA_KIND = "com.termux.localgames.extra.CUSTOM_INSTALL_KIND";
    public static final String EXTRA_TRANSLATOR =
        "com.termux.localgames.extra.CUSTOM_INSTALL_TRANSLATOR";
    public static final String EXTRA_DISPLAY_NAME =
        "com.termux.localgames.extra.CUSTOM_INSTALL_DISPLAY_NAME";
    public static final String EXTRA_PAYLOAD_PATH =
        "com.termux.localgames.extra.CUSTOM_INSTALL_PAYLOAD_PATH";
    public static final String EXTRA_SHA256 = "com.termux.localgames.extra.CUSTOM_INSTALL_SHA256";

    private CustomComponentInstallTasks() {}

    /** @param translator required for WINE kind, must be null for BOX64 kind.
     *  @param stagedPayload already drained into app-private storage (see
     *         GameStoragePaths#getCustomComponentStagingDirectory()) before this is called. */
    @NonNull
    public static String enqueue(@NonNull Context context, @NonNull String componentId,
            @NonNull CustomRuntimeComponent.Kind kind, @Nullable RuntimeTranslator translator,
            @NonNull String displayName, @NonNull File stagedPayload, @NonNull String sha256) {
        try {
            FileCustomComponentInstallTaskRepository repository =
                new FileCustomComponentInstallTaskRepository(new GameStoragePaths(
                    context.getFilesDir()).getCustomComponentInstallTasksDirectory());
            // Guard against re-enqueuing the same componentId twice concurrently -- unlike
            // backup/restore (which share one external file), different componentIds never
            // collide with each other, so this is scoped per-id rather than "at most one in
            // flight at all".
            for (CustomComponentInstallTask task : repository.list()) {
                if (!task.getState().isTerminal() && componentId.equals(task.getComponentId())) {
                    reconcileAll(context);
                    return task.getTaskId();
                }
            }
        } catch (IOException ignored) {
            // The foreground owner will persist a stable failure if storage is unavailable.
        }
        String taskId = "custom-install-" + UUID.randomUUID();
        Intent intent = new Intent(context, CustomComponentInstallForegroundService.class)
            .setAction(ACTION_ENQUEUE)
            .putExtra(EXTRA_TASK_ID, taskId)
            .putExtra(EXTRA_COMPONENT_ID, componentId)
            .putExtra(EXTRA_KIND, kind.name())
            .putExtra(EXTRA_TRANSLATOR, translator == null ? "" : translator.getStorageValue())
            .putExtra(EXTRA_DISPLAY_NAME, displayName)
            .putExtra(EXTRA_PAYLOAD_PATH, stagedPayload.getAbsolutePath())
            .putExtra(EXTRA_SHA256, sha256);
        start(context, intent);
        return taskId;
    }

    public static void reconcile(@NonNull Context context, @NonNull String taskId) {
        requireId(taskId);
        start(context, new Intent(context, CustomComponentInstallForegroundService.class)
            .setAction(ACTION_RECONCILE).putExtra(EXTRA_TASK_ID, taskId));
    }

    public static void reconcileAll(@NonNull Context context) {
        start(context, new Intent(context, CustomComponentInstallForegroundService.class)
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
