package com.termux.ui.wine.data;

import com.termux.ui.wine.domain.RuntimeSetupTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Private non-executable inputs: unchanged v3 for legacy recipes, Base64 v4 for frozen bindings. */
public final class RootfsSetupSpecCodec {
    public void write(File file, RuntimeSetupTask task, File recipeDirectory,
                      Map<String, File> componentDirectories, File buildContext,
                      File eventsPath, File logPath, String winePackage,
                      File winePrefixDirectory, File homeDirectory,
                      File prefixWarmupScript) throws IOException {
        write(file, task, recipeDirectory, componentDirectories, buildContext, eventsPath, logPath,
            winePackage, winePrefixDirectory, homeDirectory, prefixWarmupScript, "", "");
    }

    public void write(File file, RuntimeSetupTask task, File recipeDirectory,
                      Map<String, File> componentDirectories, File buildContext,
                      File eventsPath, File logPath, String winePackage,
                      File winePrefixDirectory, File homeDirectory,
                      File prefixWarmupScript, String runtimeTranslator,
                      String box64ComponentId) throws IOException {
        write(file, task, recipeDirectory, componentDirectories, buildContext, eventsPath, logPath,
            winePackage, winePrefixDirectory, homeDirectory, prefixWarmupScript, runtimeTranslator,
            box64ComponentId, null);
    }

    public void write(File file, RuntimeSetupTask task, File recipeDirectory,
                      Map<String, File> componentDirectories, File buildContext,
                      File eventsPath, File logPath, String winePackage,
                      File winePrefixDirectory, File homeDirectory,
                      File prefixWarmupScript, String runtimeTranslator,
                      String box64ComponentId,
                      java.util.List<com.termux.ui.wine.domain.ProotComponentBinding> bindings) throws IOException {
        if (file == null || task == null) throw new IllegalArgumentException("setup spec required");
        if (componentDirectories == null || componentDirectories.isEmpty()) {
            throw new IllegalArgumentException("component directories required");
        }
        if (bindings != null) {
            writeV4(file, task, recipeDirectory, componentDirectories, buildContext, eventsPath,
                logPath, winePackage, winePrefixDirectory, homeDirectory, prefixWarmupScript,
                runtimeTranslator, box64ComponentId, bindings);
            return;
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
        text(value, "runtimeTranslator", runtimeTranslator);
        text(value, "box64ComponentId", box64ComponentId);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("setup_spec_directory_failed");
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        }
        if (!temporary.renameTo(file)) throw new IOException("setup_spec_publish_failed");
    }

    private static void writeV4(File file, RuntimeSetupTask task, File recipeDirectory,
                                Map<String, File> components, File buildContext, File eventsPath,
                                File logPath, String winePackage, File prefix, File home,
                                File warmup, String translator, String box64,
                                java.util.List<com.termux.ui.wine.domain.ProotComponentBinding> bindings)
        throws IOException {
        if (components.size() > 16) throw new IllegalArgumentException("too_many_setup_components");
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add("schemaVersion=4");
        encoded(lines, "taskId", task.getTaskId());
        encoded(lines, "packageName", task.getPackageName());
        encoded(lines, "version", Integer.toString(task.getVersion()));
        encoded(lines, "recipeSha256", task.getRecipeSha256());
        encoded(lines, "containerId", task.getContainerId());
        encoded(lines, "containerName", task.getContainerName());
        encoded(lines, "baseOnly", Boolean.toString(task.isBaseOnly()));
        encoded(lines, "buildContext", canonical(buildContext));
        encoded(lines, "recipeDirectory", canonical(recipeDirectory));
        lines.add("componentCount=" + components.size());
        int index = 0;
        for (Map.Entry<String, File> component : components.entrySet()) {
            if (component.getKey() == null || !component.getKey().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new IllegalArgumentException("invalid_setup_component_id");
            }
            encoded(lines, "component." + index + ".id", component.getKey());
            encoded(lines, "component." + index + ".directory", canonical(component.getValue()));
            index++;
        }
        encoded(lines, "eventsPath", canonical(eventsPath));
        encoded(lines, "logPath", canonical(logPath));
        encoded(lines, "winePackage", winePackage == null ? "" : winePackage);
        encoded(lines, "winePrefixDirectory", canonicalOrEmpty(prefix));
        encoded(lines, "homeDirectory", canonicalOrEmpty(home));
        encoded(lines, "prefixWarmupScript", canonicalOrEmpty(warmup));
        encoded(lines, "runtimeTranslator", translator);
        encoded(lines, "box64ComponentId", box64);
        ProotBindingsCodec.append(lines, bindings);
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("setup_spec_directory_failed");
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            for (String line : lines) out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            out.getFD().sync();
        }
        if (!temporary.renameTo(file)) throw new IOException("setup_spec_publish_failed");
    }

    private static void encoded(java.util.List<String> lines, String key, String value) {
        if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("invalid_setup_spec_value:" + key);
        }
        lines.add(key + "=" + Base64Text.encode(value.getBytes(StandardCharsets.UTF_8)));
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
