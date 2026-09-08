package com.termux.localgames.service;

import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.domain.ComponentTask;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Durable human-readable state stream for the foreground component console. */
public final class ComponentTaskConsoleLog {
    private ComponentTaskConsoleLog() { }

    public static File file(File filesDirectory, String taskId) {
        requireTaskId(taskId);
        return new File(new ComponentStoragePaths(filesDirectory).getRoot(), "logs/" + taskId + ".log");
    }

    public static synchronized void append(File filesDirectory, String taskId, String message) {
        if (message == null || message.trim().isEmpty()) return;
        try {
            File file = file(filesDirectory, taskId);
            File parent = file.getParentFile();
            if (!parent.isDirectory() && !parent.mkdirs()) return;
            try (BufferedWriter output = new BufferedWriter(new FileWriter(file, true))) {
                String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
                output.write("[" + timestamp + "] " + message.replace('\n', ' '));
                output.newLine();
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // The durable task itself remains authoritative if log storage is unavailable.
        }
    }

    public static String stateLine(ComponentTask task) {
        String progress = task.getExpectedSize() > 0
            ? " " + task.getDownloadedBytes() + "/" + task.getExpectedSize() + " bytes (" +
                Math.min(100, task.getDownloadedBytes() * 100L / task.getExpectedSize()) + "%)"
            : " " + task.getDownloadedBytes() + " bytes";
        String error = task.getErrorMessage().isEmpty() ? "" : " · " + task.getErrorMessage();
        return task.getPackageName() + " · " + task.getState().name() + progress + error;
    }

    private static void requireTaskId(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("component_task_id_invalid");
        }
    }
}
