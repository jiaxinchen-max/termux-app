package com.termux.localgames.data;

import com.termux.localgames.domain.RuntimeSetupTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes a non-executable, private-path-only shell input contract. */
public final class RootfsSetupSpecCodec {
    public void write(File file, RuntimeSetupTask task, File recipeDirectory,
                      File sourceDirectory, File buildContext, File metadataRoot,
                      File eventsPath, File logPath, String winePackage,
                      File winePrefixDirectory, File prefixWarmupScript) throws IOException {
        if (file == null || task == null) throw new IllegalArgumentException("setup spec required");
        StringBuilder value = new StringBuilder("schemaVersion=2\n");
        text(value, "taskId", task.getTaskId());
        text(value, "packageName", task.getPackageName());
        value.append("version=").append(task.getVersion()).append('\n');
        value.append("recipeSha256=").append(task.getRecipeSha256()).append('\n');
        text(value, "containerId", task.getContainerId());
        text(value, "containerName", task.getContainerName());
        text(value, "baseOnly", String.valueOf(task.isBaseOnly()));
        text(value, "buildContext", canonical(buildContext));
        text(value, "recipeDirectory", canonical(recipeDirectory));
        text(value, "sourceDirectory", canonical(sourceDirectory));
        text(value, "metadataRoot", canonical(metadataRoot));
        text(value, "eventsPath", canonical(eventsPath));
        text(value, "logPath", canonical(logPath));
        // Empty for BASE_ONLY tasks (no per-game container/prefix involved) -- the script never
        // reaches the warmup call site in that case.
        text(value, "winePackage", winePackage == null ? "" : winePackage);
        text(value, "winePrefixDirectory", canonicalOrEmpty(winePrefixDirectory));
        text(value, "prefixWarmupScript", canonicalOrEmpty(prefixWarmupScript));
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("setup_spec_directory_failed");
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("setup_spec_replace_failed");
        if (!temporary.renameTo(file)) throw new IOException("setup_spec_publish_failed");
    }

    private static void text(StringBuilder output, String key, String value) {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 ||
            value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("invalid setup spec value: " + key);
        }
        output.append(key).append('=').append(value).append('\n');
    }

    private static String canonical(File file) throws IOException {
        if (file == null) throw new IOException("setup_spec_path_missing");
        return file.getCanonicalPath();
    }

    private static String canonicalOrEmpty(File file) throws IOException {
        return file == null ? "" : file.getCanonicalPath();
    }
}
