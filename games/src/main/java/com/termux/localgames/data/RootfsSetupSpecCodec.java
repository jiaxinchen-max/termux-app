package com.termux.localgames.data;

import com.termux.localgames.domain.RuntimeSetupTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Writes a non-executable, private-path-only shell input contract. */
public final class RootfsSetupSpecCodec {
    public void write(File file, RuntimeSetupTask task, File recipeDirectory,
                      Map<String, File> componentDirectories, File buildContext,
                      File eventsPath, File logPath, String winePackage,
                      File winePrefixDirectory, File homeDirectory,
                      File prefixWarmupScript) throws IOException {
        if (file == null || task == null) throw new IllegalArgumentException("setup spec required");
        if (componentDirectories == null || componentDirectories.isEmpty()) {
            throw new IllegalArgumentException("component directories required");
        }
        StringBuilder value = new StringBuilder("schemaVersion=3\n");
        text(value, "taskId", task.getTaskId());
        text(value, "packageName", task.getPackageName());
        value.append("version=").append(task.getVersion()).append('\n');
        value.append("recipeSha256=").append(task.getRecipeSha256()).append('\n');
        text(value, "containerId", task.getContainerId());
        text(value, "containerName", task.getContainerName());
        text(value, "baseOnly", String.valueOf(task.isBaseOnly()));
        text(value, "buildContext", canonical(buildContext));
        text(value, "recipeDirectory", canonical(recipeDirectory));
        // One line per base component: component=<id>|<canonical dir>. The guest stages each into
        // $BUILD_CONTEXT/components/<id>/ and installs it by content (apt/dpkg/dxvk-copy).
        for (Map.Entry<String, File> entry : componentDirectories.entrySet()) {
            String id = entry.getKey();
            if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) {
                throw new IllegalArgumentException("invalid component id: " + id);
            }
            text(value, "component", id + "|" + canonical(entry.getValue()));
        }
        text(value, "eventsPath", canonical(eventsPath));
        text(value, "logPath", canonical(logPath));
        // Empty for BASE_ONLY tasks (no per-game container/prefix involved) -- the script never
        // reaches the warmup call site in that case.
        text(value, "winePackage", winePackage == null ? "" : winePackage);
        text(value, "winePrefixDirectory", canonicalOrEmpty(winePrefixDirectory));
        text(value, "homeDirectory", canonicalOrEmpty(homeDirectory));
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
