package com.termux.localgames.service;

import android.content.Context;
import android.os.Build;

import com.termux.localgames.api.LaunchTasks;
import com.termux.localgames.data.FileLaunchTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.runtime.LocalGameOrchestrator;
import com.termux.localgames.runtime.OrchestrationException;
import com.termux.localgames.runtime.Subscription;
import com.termux.localgames.runtime.TaskObserver;
import com.termux.localgames.recovery.GameAssetMutationLock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Persistent command/query implementation; process work remains owned by the Service. */
public final class PersistentLocalGameOrchestrator implements LocalGameOrchestrator {

    private static final ScheduledExecutorService OBSERVER = Executors.newSingleThreadScheduledExecutor(
        runnable -> new Thread(runnable, "GamesLaunchObserver"));

    private final Context context;
    private final FileLaunchTaskRepository repository;
    private final GameStoragePaths paths;

    public PersistentLocalGameOrchestrator(Context context) {
        this.context = context.getApplicationContext();
        this.paths = new GameStoragePaths(context.getFilesDir());
        this.repository = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
    }

    @Override
    public String launch(String gameId) throws OrchestrationException {
        try {
            String taskId;
            boolean[] created = {false};
            taskId = GameAssetMutationLock.call(() -> {
                Optional<LaunchTask> active = repository.findActiveForGame(gameId);
                if (active.isPresent()) {
                    return active.get().getTaskId();
                }
                String createdTaskId = "launch-" + UUID.randomUUID().toString();
                repository.save(LaunchTask.queued(createdTaskId, gameId, System.currentTimeMillis()));
                created[0] = true;
                return createdTaskId;
            });
            start(LaunchTasks.command(context,
                created[0] ? LaunchTasks.ACTION_EXECUTE : LaunchTasks.ACTION_RECONCILE,
                taskId));
            return taskId;
        } catch (IOException | RuntimeException error) {
            throw failure("launch_task_create_failed", error);
        }
    }

    @Override
    public void cancel(String taskId, boolean force) throws OrchestrationException {
        try {
            Optional<LaunchTask> task = repository.find(taskId);
            if (!task.isPresent() || task.get().getState().isTerminal()) return;
            File marker = new File(paths.getLaunchCancelDirectory(), taskId + ".cancel");
            File parent = marker.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("cancel_directory_failed");
            try (FileOutputStream output = new FileOutputStream(marker)) {
                output.write(taskId.getBytes(StandardCharsets.US_ASCII));
                output.flush();
                output.getFD().sync();
            }
            android.content.Intent intent = LaunchTasks.command(context, LaunchTasks.ACTION_CANCEL, taskId)
                .putExtra(LaunchTasks.EXTRA_FORCE, force);
            start(intent);
        } catch (IOException | RuntimeException error) {
            throw failure("launch_cancel_failed", error);
        }
    }

    @Override
    public void reportDisplayConnection(String taskId, boolean connected) throws OrchestrationException {
        report(LaunchTasks.command(context, LaunchTasks.ACTION_DISPLAY_CONNECTION, taskId)
            .putExtra(LaunchTasks.EXTRA_DISPLAY_CONNECTED, connected));
    }

    @Override
    public void reportFirstFrame(String taskId) throws OrchestrationException {
        report(LaunchTasks.command(context, LaunchTasks.ACTION_FIRST_FRAME, taskId));
    }

    @Override
    public Optional<LaunchTask> findTask(String taskId) throws OrchestrationException {
        try { return repository.find(taskId); }
        catch (IOException | RuntimeException error) {
            throw failure("launch_task_read_failed", error);
        }
    }

    @Override
    public Subscription observe(String taskId, TaskObserver<LaunchTask> observer)
        throws OrchestrationException {
        if (observer == null) throw new OrchestrationException("launch_observer_required",
            "launch_observer_required", false, null);
        Optional<LaunchTask> initial = findTask(taskId);
        if (initial.isPresent() && !initial.get().getState().isTerminal()) {
            start(LaunchTasks.command(context, LaunchTasks.ACTION_RECONCILE, taskId));
        }
        final long[] fingerprint = {-1};
        ScheduledFuture<?> future = OBSERVER.scheduleWithFixedDelay(() -> {
            try {
                LaunchTask task = repository.find(taskId).orElse(null);
                if (task == null) return;
                long current = task.getUpdatedAt() * 31 + task.getLastEventSequence();
                if (current != fingerprint[0]) {
                    fingerprint[0] = current;
                    observer.onChanged(task);
                }
            } catch (Throwable error) {
                observer.onError(error);
            }
        }, 0, 500, TimeUnit.MILLISECONDS);
        return () -> future.cancel(false);
    }

    private void start(android.content.Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private void report(android.content.Intent intent) throws OrchestrationException {
        try { start(intent); }
        catch (RuntimeException error) { throw failure("launch_display_report_failed", error); }
    }

    private static OrchestrationException failure(String code, Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isEmpty()) message = code;
        return new OrchestrationException(code, message, true, error);
    }
}
