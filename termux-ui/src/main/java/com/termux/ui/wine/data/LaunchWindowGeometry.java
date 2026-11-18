package com.termux.ui.wine.data;

import com.termux.ui.wine.runtime.WineDisplayPolicy;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Task-private request survives service preparation and process recreation. */
public final class LaunchWindowGeometry {
    private final File directory;
    public LaunchWindowGeometry(File directory) { this.directory = directory; }

    private File file(String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}"))
            throw new IllegalArgumentException("invalid_task_id");
        return new File(directory, taskId + ".window");
    }

    public void write(String taskId, int width, int height) throws IOException {
        String output = WineDisplayPolicy.resolve(WineDisplayPolicy.NATIVE, width, height);
        File target = file(taskId);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("window_request_directory_failed");
        File temporary = new File(target.getPath() + ".tmp");
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(output.getBytes(StandardCharsets.US_ASCII));
                stream.getFD().sync();
            }
            if (!temporary.renameTo(target)) throw new IOException("window_request_publish_failed");
        } finally { if (temporary.exists()) temporary.delete(); }
    }

    public String read(String taskId) throws IOException {
        File source = file(taskId);
        if (!source.isFile()) throw new IOException("window_size_required");
        if (source.length() > 32) throw new IOException("invalid_window_request");
        try {
            return WineDisplayPolicy.requireNumeric(new String(Files.readAllBytes(source.toPath()), StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException error) { throw new IOException("invalid_window_request", error); }
    }

    public void delete(String taskId) throws IOException {
        File target = file(taskId);
        if (target.exists() && !target.delete()) throw new IOException("window_request_cleanup_failed");
    }
}
