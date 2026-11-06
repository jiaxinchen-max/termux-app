package com.termux.ui.data;

import com.termux.ui.domain.CustomComponentInstallTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes the non-executable archive-install contract with a mandatory backend and payload hash. */
public final class CustomComponentInstallSpecCodec {
    public void write(File file, CustomComponentInstallTask task, File payloadPath,
                      File eventsPath, File logPath) throws IOException {
        if (file == null || task == null) {
            throw new IllegalArgumentException("custom install spec required");
        }
        String kind = task.getKind().name().toLowerCase(java.util.Locale.ROOT);
        StringBuilder value = new StringBuilder("schemaVersion=1\n");
        text(value, "taskId", task.getTaskId());
        text(value, "kind", kind);
        text(value, "runtimeBackend", task.getBackendType().getStorageValue());
        text(value, "runtimeTranslator", task.getTranslator() == null
            ? "none" : task.getTranslator().getStorageValue());
        text(value, "componentId", task.getComponentId());
        text(value, "payloadPath", canonical(payloadPath));
        text(value, "payloadKind", kind + "-tree");
        text(value, "payloadSha256", task.getSha256());
        text(value, "eventsPath", canonical(eventsPath));
        text(value, "logPath", canonical(logPath));
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("custom_install_spec_directory_failed");
        }
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("custom_install_spec_replace_failed");
        if (!temporary.renameTo(file)) throw new IOException("custom_install_spec_publish_failed");
    }

    private static void text(StringBuilder output, String key, String value) {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 ||
            value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("invalid custom install spec value: " + key);
        }
        output.append(key).append('=').append(value).append('\n');
    }

    private static String canonical(File file) throws IOException {
        if (file == null) throw new IOException("custom_install_spec_path_missing");
        return file.getCanonicalPath();
    }
}
