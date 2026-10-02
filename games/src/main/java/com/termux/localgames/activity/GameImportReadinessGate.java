package com.termux.localgames.activity;

import android.app.Activity;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.localgames.R;
import com.termux.localgames.api.ComponentTasks;
import com.termux.localgames.api.RuntimeSetupTasks;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.RuntimeReadinessState;
import com.termux.localgames.runtime.RuntimeEnvironmentStatus;

import java.util.UUID;
import java.util.concurrent.ExecutorService;

/** Fail-fast "ToC" gate shared by every game-import entry point (the main-screen "Import game"
 *  actions in LocalGamesActivity and GameImportActivity's own onCreate): both the GLIBC and
 *  RootFS runtimes must be READY before the user is let into the import flow, so a missing
 *  runtime is caught at tap-time instead of after scanning a folder and picking an executable.
 *  Readiness is checked on a background executor (file I/O) and resolved back onto the main
 *  thread, mirroring LocalGamesActivity.renderRuntimeStatus()'s own threading pattern. */
final class GameImportReadinessGate {
    private static final String TERMUX_GLIBC_RUNTIME_COMPONENT = "termux-glibc-runtime";

    private GameImportReadinessGate() {}

    static void require(@NonNull Activity activity, @NonNull ExecutorService backgroundExecutor,
                        @NonNull Runnable onReady) {
        backgroundExecutor.execute(() -> {
            RuntimeEnvironmentStatus status =
                new RuntimeEnvironmentStatus(new GameStoragePaths(activity.getFilesDir()));
            boolean glibcReady = status.glibcState() == RuntimeReadinessState.READY;
            boolean rootfsReady = status.rootfsState() == RuntimeReadinessState.READY;
            activity.runOnUiThread(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                if (glibcReady && rootfsReady) onReady.run();
                else showGateDialog(activity, backgroundExecutor, onReady, glibcReady, rootfsReady);
            });
        });
    }

    private static void showGateDialog(Activity activity, ExecutorService backgroundExecutor,
                                       Runnable onReady, boolean glibcReady, boolean rootfsReady) {
        StringBuilder message = new StringBuilder();
        if (!glibcReady) {
            message.append(activity.getString(R.string.local_games_import_gate_glibc_missing));
        }
        if (!rootfsReady) {
            if (message.length() > 0) message.append('\n');
            message.append(activity.getString(R.string.local_games_import_gate_rootfs_missing));
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.local_games_import_gate_title)
            .setMessage(message.toString())
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> activity.finish());
        if (!glibcReady) {
            builder.setPositiveButton(R.string.local_games_import_gate_build_glibc,
                (dialog, which) -> {
                    String taskId = ComponentTasks.enqueue(activity,
                        TERMUX_GLIBC_RUNTIME_COMPONENT);
                    RuntimeSetupConsoleDialog.show(activity, taskId).setOnDismissListener(
                        consoleDialog -> require(activity, backgroundExecutor, onReady));
                });
        }
        if (!rootfsReady) {
            Runnable buildRootfs = () -> {
                String taskId = RuntimeSetupTasks.enqueueBaseOnly(activity,
                    GameContainer.ROOTFS_RUNTIME_PACKAGE, "shared-rebuild-" + UUID.randomUUID());
                RuntimeSetupConsoleDialog.show(activity, taskId).setOnDismissListener(
                    consoleDialog -> require(activity, backgroundExecutor, onReady));
            };
            if (!glibcReady) {
                builder.setNeutralButton(R.string.local_games_import_gate_build_rootfs,
                    (dialog, which) -> buildRootfs.run());
            } else {
                builder.setPositiveButton(R.string.local_games_import_gate_build_rootfs,
                    (dialog, which) -> buildRootfs.run());
            }
        }
        builder.show();
    }
}
