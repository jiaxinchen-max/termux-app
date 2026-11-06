package com.termux.ui.api;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.ui.data.FileCustomComponentInstallTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.CustomComponentInstallTask;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.service.CustomComponentInstallForegroundService;
import com.termux.ui.wine.api.RootfsBackupTasks;
import com.termux.ui.wine.domain.RuntimeTranslator;
import com.termux.ui.wine.domain.GameRuntimeBackendType;

import java.io.File;
import java.io.IOException;
import java.util.UUID;

/** Public command surface for installing a user-picked local Wine/Box64 build into the shared
 *  RootFS -- mirrors RootfsBackupTasks's Intent-wrapping shape. */
public final class CustomComponentInstallTasks {
    public static final String ACTION_ENQUEUE =
        "com.termux.ui.action.ENQUEUE_CUSTOM_COMPONENT_INSTALL";
    public static final String ACTION_RECONCILE =
        "com.termux.ui.action.RECONCILE_CUSTOM_COMPONENT_INSTALL";
    public static final String ACTION_RECONCILE_ALL =
        "com.termux.ui.action.RECONCILE_ALL_CUSTOM_COMPONENT_INSTALL";
    public static final String EXTRA_TASK_ID = "com.termux.ui.extra.CUSTOM_INSTALL_TASK_ID";
    public static final String EXTRA_COMPONENT_ID =
        "com.termux.ui.extra.CUSTOM_INSTALL_COMPONENT_ID";
    public static final String EXTRA_KIND = "com.termux.ui.extra.CUSTOM_INSTALL_KIND";
    public static final String EXTRA_TRANSLATOR =
        "com.termux.ui.extra.CUSTOM_INSTALL_TRANSLATOR";
    public static final String EXTRA_DISPLAY_NAME =
        "com.termux.ui.extra.CUSTOM_INSTALL_DISPLAY_NAME";
    public static final String EXTRA_PAYLOAD_PATH =
        "com.termux.ui.extra.CUSTOM_INSTALL_PAYLOAD_PATH";
    public static final String EXTRA_SHA256 = "com.termux.ui.extra.CUSTOM_INSTALL_SHA256";

    public static final String EXTRA_BACKEND = "com.termux.ui.extra.CUSTOM_INSTALL_BACKEND";

    private CustomComponentInstallTasks() {}

    /** translator is required only for PRoot Wine; backendType always selects the install target.
     * stagedPayload must already be drained into the app's custom-component staging directory. */
    @NonNull
    public static String enqueue(@NonNull Context context, @NonNull String componentId,
            @NonNull CustomRuntimeComponent.Kind kind, @Nullable RuntimeTranslator translator,
            @NonNull String displayName, @NonNull File stagedPayload, @NonNull String sha256,
            @NonNull GameRuntimeBackendType backendType) {
        // Validate before sending an Intent so invalid combinations never enqueue work.
        new CustomRuntimeComponent(componentId, kind, translator, displayName, 1, sha256, backendType);
        try {
            FileCustomComponentInstallTaskRepository repository =
                new FileCustomComponentInstallTaskRepository(new GameStoragePaths(
                    context.getFilesDir()).getCustomComponentInstallTasksDirectory());
            // Guard against re-enqueuing the same componentId twice concurrently -- unlike
            // backup/restore (which share one external file), different componentIds never
            // collide with each other, so this is scoped per-id rather than "at most one in
            // flight at all".
            for (CustomComponentInstallTask task : repository.list()) {
                if (!task.getState().isTerminal() && componentId.equals(task.getComponentId()) &&
                    backendType == task.getBackendType()) {
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
            .putExtra(EXTRA_BACKEND, backendType.getStorageValue())
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
