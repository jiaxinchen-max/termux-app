package com.termux.ui.wine.activity;

import android.app.Activity;
import android.os.Environment;
import com.termux.ui.R;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.activity.RuntimeSetupConsoleDialog;
import com.termux.ui.activity.SharedStorageChooserDialog;
import com.termux.ui.wine.api.WineCDriveBackupTasks;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.recovery.WineCDriveArchive;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Per-configuration archive picker and progress; the service owns all long-running work. */
public final class WineCDriveBackupFlow {
    private final Activity activity;
    private final WineCDriveBackupTasks tasks;
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private RuntimeSetupConsoleDialog console;
    private SharedStorageChooserDialog chooser;
    private boolean destroyed;
    public WineCDriveBackupFlow(Activity activity) { this.activity = activity; tasks = new WineCDriveBackupTasks(activity); }
    public void resume() {
        reader.execute(() -> {
            try {
                WineCDriveBackupTasks.Snapshot snapshot = tasks.snapshot();
                activity.runOnUiThread(() -> { if (!destroyed && snapshot.status.equals("RUNNING")) watch(snapshot.taskId); });
            } catch (IOException error) { failure(error); }
        });
    }
    public void show(Game configuration) {
        new FrostedDialogBuilder(activity).setTitle(R.string.wine_cdrive_title)
            .setItems(new String[] {activity.getString(R.string.wine_cdrive_backup), activity.getString(R.string.wine_cdrive_restore)},
                (dialog, which) -> choose(configuration, which == 0)).show();
    }
    private void choose(Game configuration, boolean backup) {
        File root = Environment.getExternalStorageDirectory();
        if (backup) {
            String name = "cdrive-" + configuration.getName().replaceAll("[^A-Za-z0-9._-]", "_") + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + WineCDriveArchive.EXTENSION;
            chooser = SharedStorageChooserDialog.chooseNewSaveFile(activity, root, name, WineCDriveArchive.EXTENSION,
                archive -> enqueue(configuration.getId(), WineCDriveBackupTasks.Operation.BACKUP, archive));
        } else {
            chooser = SharedStorageChooserDialog.chooseArchive(activity, root, WineCDriveArchive.EXTENSION,
                archive -> new FrostedDialogBuilder(activity).setTitle(R.string.wine_cdrive_restore)
                    .setMessage(R.string.wine_cdrive_restore_confirm).setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.wine_cdrive_restore, (dialog, which) ->
                        enqueue(configuration.getId(), WineCDriveBackupTasks.Operation.RESTORE, archive)).show());
        }
    }
    private void enqueue(String configurationId, WineCDriveBackupTasks.Operation operation, File archive) {
        reader.execute(() -> {
            try {
                String taskId = tasks.enqueue(configurationId, operation, archive);
                activity.runOnUiThread(() -> { if (!destroyed) watch(taskId); });
            } catch (IOException | RuntimeException error) { failure(error); }
        });
    }
    private void watch(String taskId) {
        if (console != null && console.isShowing()) return;
        console = RuntimeSetupConsoleDialog.show(activity, taskId, R.string.wine_cdrive_title, id -> {
            try {
                WineCDriveBackupTasks.Snapshot snapshot = tasks.snapshot();
                if (!snapshot.taskId.equals(id)) return new RuntimeSetupConsoleDialog.TaskStatusLookup.Status(true, false, "cdrive_task_replaced");
                return new RuntimeSetupConsoleDialog.TaskStatusLookup.Status(snapshot.status.equals("FAILED"), false,
                    snapshot.status.equals("SUCCEEDED"), snapshot.error.isEmpty() ? snapshot.status : snapshot.error);
            } catch (IOException error) { return new RuntimeSetupConsoleDialog.TaskStatusLookup.Status(true, false, error.getMessage()); }
        }).setOutcomeMessages(R.string.wine_cdrive_completed, R.string.wine_cdrive_failed);
    }
    private void failure(Exception error) {
        activity.runOnUiThread(() -> {
            if (!destroyed) new FrostedDialogBuilder(activity).setTitle(R.string.wine_cdrive_title)
                .setMessage(error.getMessage()).setPositiveButton(android.R.string.ok, null).show();
        });
    }
    public void release() {
        destroyed = true; reader.shutdownNow();
        if (console != null) console.dismiss();
        if (chooser != null) chooser.dismiss();
    }
}
