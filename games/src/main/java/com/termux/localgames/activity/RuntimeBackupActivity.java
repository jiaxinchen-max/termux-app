package com.termux.localgames.activity;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.localgames.R;
import com.termux.localgames.api.LaunchHostException;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.RuntimeProvisionRequest;
import com.termux.localgames.databinding.ActivityRuntimeBackupBinding;
import com.termux.localgames.runtime.RuntimeBackupManager;
import com.termux.localgames.runtime.RuntimeBackupType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** SAF bridge around a visible Termux {@code tar} session. */
public final class RuntimeBackupActivity extends AppCompatActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesRuntimeBackupSaf"));
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityRuntimeBackupBinding binding;
    private RuntimeBackupManager manager;
    private RuntimeBackupType pendingExport;
    private RuntimeBackupManager.Job activeJob;
    @Nullable private Uri exportDestination;
    private boolean destroyed;

    private final ActivityResultLauncher<String> createArchive = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("application/x-tar"), uri -> {
            RuntimeBackupType type = pendingExport;
            pendingExport = null;
            if (uri != null && type != null) prepareExport(type, uri);
        });
    private final ActivityResultLauncher<String[]> openArchive = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) confirmRestore(uri);
        });

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        manager = new RuntimeBackupManager(this, getFilesDir());
        binding = ActivityRuntimeBackupBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.runtimeBackupToolbar.setNavigationOnClickListener(view -> finish());
        GamesHelpDialog.attach(binding.runtimeBackupToolbar,
            R.string.local_games_runtime_backup_title,
            R.string.local_games_runtime_backup_description);
        binding.runtimeBackupExportGlibc.setOnClickListener(view -> requestExport(RuntimeBackupType.GLIBC));
        binding.runtimeBackupExportRootfs.setOnClickListener(view -> requestExport(RuntimeBackupType.ROOTFS_PROOT));
        binding.runtimeBackupRestore.setOnClickListener(view ->
            openArchive.launch(new String[] {"application/x-tar", "application/octet-stream"}));
        binding.runtimeBackupViewProcess.setOnClickListener(view -> showActiveConsole());
    }

    private void requestExport(RuntimeBackupType type) {
        pendingExport = type;
        String suffix = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        createArchive.launch("termux-games-" + type.getStorageValue() + "-" + suffix + ".tar");
    }

    private void confirmRestore(Uri uri) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_games_runtime_backup_restore_title)
            .setMessage(R.string.local_games_runtime_backup_restore_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_runtime_backup_restore, (dialog, which) ->
                prepareRestore(uri))
            .show();
    }

    private void prepareExport(RuntimeBackupType type, Uri destination) {
        setBusy(true);
        executor.execute(() -> {
            try {
                RuntimeBackupManager.Job job = manager.prepareExport(type);
                runOnUiThread(() -> startJob(job, destination));
            } catch (IOException | RuntimeException error) {
                failOnUi(error);
            }
        });
    }

    private void prepareRestore(Uri source) {
        setBusy(true);
        executor.execute(() -> {
            try (InputStream input = getContentResolver().openInputStream(source)) {
                if (input == null) throw new IOException("runtime_backup_source_unavailable");
                RuntimeBackupManager.Job job = manager.prepareRestore(input);
                runOnUiThread(() -> startJob(job, null));
            } catch (IOException | RuntimeException error) {
                failOnUi(error);
            }
        });
    }

    private void startJob(RuntimeBackupManager.Job job, @Nullable Uri destination) {
        if (destroyed) {
            manager.cleanup(job);
            return;
        }
        activeJob = job;
        exportDestination = destination;
        binding.runtimeBackupViewProcess.setVisibility(View.VISIBLE);
        showActiveConsole();
        try {
            LocalGames.requireHost(this).startRuntimeProvision(new RuntimeProvisionRequest(
                job.getTaskId(), job.getScript().getCanonicalPath(), job.getSpec().getCanonicalPath(),
                job.getDirectory().getCanonicalPath()));
            handler.postDelayed(this::pollJob, 300);
        } catch (IOException | LaunchHostException | RuntimeException error) {
            completeFailure(error);
        }
    }

    private void showActiveConsole() {
        if (activeJob == null) return;
        RuntimeProvisionConsoleDialog.show(this, activeJob.getTaskId(),
            R.string.local_games_runtime_backup_console_title);
    }

    private void pollJob() {
        if (destroyed || activeJob == null) return;
        try {
            RuntimeBackupManager.Outcome outcome = manager.readOutcome(activeJob);
            if (outcome == null) {
                handler.postDelayed(this::pollJob, 500);
            } else if (!outcome.isSuccess()) {
                completeFailure(new IOException(outcome.getError()));
            } else if (activeJob.isExport()) {
                copyExportToSaf(outcome.getType());
            } else {
                completeSuccess(outcome.getType());
            }
        } catch (IOException | RuntimeException error) {
            completeFailure(error);
        }
    }

    private void copyExportToSaf(RuntimeBackupType type) {
        Uri destination = exportDestination;
        RuntimeBackupManager.Job job = activeJob;
        if (destination == null || job == null) {
            completeFailure(new IOException("runtime_backup_destination_unavailable"));
            return;
        }
        // The PTY has already completed. Do not offer a second console that can no longer attach
        // while the unavoidable SAF stream copy is in progress.
        binding.runtimeBackupViewProcess.setVisibility(View.GONE);
        binding.runtimeBackupStatus.setText(R.string.local_games_runtime_backup_copying_output);
        binding.runtimeBackupStatus.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(destination, "w")) {
                if (output == null) throw new IOException("runtime_backup_destination_unavailable");
                manager.copyExportTo(job, output, (copiedBytes, totalBytes) ->
                    runOnUiThread(() -> updateCopyProgress(job, copiedBytes, totalBytes)));
                runOnUiThread(() -> completeSuccess(type));
            } catch (IOException | RuntimeException error) {
                failOnUi(error);
            }
        });
    }

    private void updateCopyProgress(RuntimeBackupManager.Job job, long copiedBytes, long totalBytes) {
        if (destroyed || activeJob != job || totalBytes <= 0L) return;
        int percent = (int) Math.min(100L, copiedBytes * 100L / totalBytes);
        binding.runtimeBackupStatus.setText(getString(
            R.string.local_games_runtime_backup_copying_output_progress, percent));
    }

    private void completeSuccess(@Nullable RuntimeBackupType type) {
        if (destroyed || activeJob == null) return;
        RuntimeBackupManager.Job job = activeJob;
        activeJob = null;
        exportDestination = null;
        handler.removeCallbacksAndMessages(null);
        setBusy(false);
        binding.runtimeBackupViewProcess.setVisibility(View.GONE);
        if (job.isExport()) {
            binding.runtimeBackupStatus.setText(R.string.local_games_runtime_backup_exported);
            binding.runtimeBackupStatus.setVisibility(View.VISIBLE);
            toast(R.string.local_games_runtime_backup_exported);
        }
        else if (type != null) {
            binding.runtimeBackupStatus.setText(getString(
                R.string.local_games_runtime_backup_restored, label(type)));
            binding.runtimeBackupStatus.setVisibility(View.VISIBLE);
        }
        manager.cleanup(job);
    }

    private void completeFailure(Throwable error) {
        if (destroyed) return;
        RuntimeBackupManager.Job job = activeJob;
        activeJob = null;
        exportDestination = null;
        handler.removeCallbacksAndMessages(null);
        setBusy(false);
        binding.runtimeBackupViewProcess.setVisibility(View.GONE);
        showError(message(error));
        if (job != null) manager.cleanup(job);
    }

    private void failOnUi(Throwable error) {
        runOnUiThread(() -> completeFailure(error));
    }

    private void setBusy(boolean busy) {
        binding.runtimeBackupProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        binding.runtimeBackupExportGlibc.setEnabled(!busy);
        binding.runtimeBackupExportRootfs.setEnabled(!busy);
        binding.runtimeBackupRestore.setEnabled(!busy);
        if (busy) {
            binding.runtimeBackupStatus.setText(R.string.local_games_runtime_backup_working);
            binding.runtimeBackupStatus.setVisibility(View.VISIBLE);
        }
    }

    private void showError(String error) {
        binding.runtimeBackupStatus.setText(getString(R.string.local_games_runtime_backup_failed, error));
        binding.runtimeBackupStatus.setVisibility(View.VISIBLE);
    }

    private int label(RuntimeBackupType type) {
        return type == RuntimeBackupType.GLIBC
            ? R.string.local_games_runtime_backup_glibc : R.string.local_games_runtime_backup_rootfs;
    }

    private void toast(int text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }

    private static String message(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }
}
