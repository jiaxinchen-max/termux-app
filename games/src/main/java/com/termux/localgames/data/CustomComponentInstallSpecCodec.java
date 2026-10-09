package com.termux.localgames.data;

import com.termux.localgames.domain.CustomComponentInstallTask;
import com.termux.localgames.domain.CustomRuntimeComponent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes a non-executable, shell input contract for install_custom_rootfs_component.sh. Unlike
 *  RootfsBackupSpecCodec's backupArchivePath, payloadPath here is always required to live under
 *  the app's own private storage (the SAF-picked file is drained there before this spec is ever
 *  written -- see CustomComponentInstallForegroundService). payloadKind is always derivable from
 *  the task's kind (WINE -> wine-tree, BOX64 -> box64-tree) -- both are always a compressed
 *  archive (see install_custom_rootfs_component.sh's header comment on why no .deb/raw-binary
 *  path exists), so there is no separate classification step or parameter for it. */
public final class CustomComponentInstallSpecCodec {
    public void write(File file, CustomComponentInstallTask task, File payloadPath,
                      File eventsPath, File logPath) throws IOException {
        if (file == null || task == null) {
            throw new IllegalArgumentException("custom install spec required");
        }
        boolean wine = task.getKind() == CustomRuntimeComponent.Kind.WINE;
        StringBuilder value = new StringBuilder("schemaVersion=1\n");
        text(value, "taskId", task.getTaskId());
        text(value, "kind", wine ? "wine" : "box64");
        text(value, "componentId", task.getComponentId());
        text(value, "payloadPath", canonical(payloadPath));
        text(value, "payloadKind", wine ? "wine-tree" : "box64-tree");
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
