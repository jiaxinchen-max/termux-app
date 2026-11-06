package com.termux.ui.activity;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.termux.ui.R;
import com.termux.ui.service.SetupPreparationLog;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Preparation output until the same log is replayed into the live terminal. */
final class SetupPreparationConsole extends FollowingLogView {
    interface FailureListener { void failed(String message); }
    private final File filesDirectory;
    private final String taskId;
    private final boolean componentTask;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService reader = Executors.newSingleThreadExecutor();
    private final FailureListener failureListener;
    private boolean stopped;
    private String previous = "";

    SetupPreparationConsole(Context context, String taskId, boolean componentTask, FailureListener failureListener) {
        super(context);
        this.filesDirectory = context.getFilesDir();
        this.taskId = taskId;
        this.componentTask = componentTask;
        this.failureListener = failureListener;
        setOutput(context.getString(R.string.local_games_runtime_setup_preparing));
    }

    void start() { refresh(); }

    void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        reader.shutdownNow();
    }

    void terminalAttached() {
        stop();
        setVisibility(GONE);
    }

    private void refresh() {
        if (stopped) return;
        reader.execute(() -> {
            String text;
            String failure;
            try {
                text = SetupPreparationLog.readTail(componentTask
                    ? com.termux.ui.service.ComponentTaskConsoleLog.file(filesDirectory, taskId)
                    : SetupPreparationLog.file(filesDirectory, taskId));
                failure = componentTask
                    ? com.termux.ui.service.ComponentTaskConsoleLog.commandFailure(filesDirectory, taskId)
                    : SetupPreparationLog.failure(filesDirectory, taskId);
            } catch (IOException error) {
                text = "log_unavailable: " + error.getMessage();
                failure = "";
            }
            final String current = text;
            final String currentFailure = failure;
            handler.post(() -> {
                if (stopped) return;
                if (!current.isEmpty() && !current.equals(previous)) {
                    setVisibility(VISIBLE);
                    previous = current;
                    setOutput(current);
                }
                if (!currentFailure.isEmpty()) failureListener.failed(currentFailure);
                else handler.postDelayed(this::refresh, 500);
            });
        });
    }
}
