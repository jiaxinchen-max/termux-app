package com.termux.localgames.data;

import com.termux.localgames.domain.RootfsBackupTask;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Writes a non-executable, shell input contract for backup_restore_rootfs.sh. Unlike
 *  RootfsSetupSpecCodec's spec, backupArchivePath is deliberately allowed to point outside the
 *  app's own private storage (it is the one path meant to live on external/shared storage). */
public final class RootfsBackupSpecCodec {
    public void write(File file, RootfsBackupTask task, File backupArchivePath,
                      File eventsPath, File logPath) throws IOException {
        if (file == null || task == null) throw new IllegalArgumentException("backup spec required");
        StringBuilder value = new StringBuilder("schemaVersion=1\n");
        text(value, "taskId", task.getTaskId());
        text(value, "mode", task.getKind() == RootfsBackupTask.Kind.BACKUP ? "backup" : "restore");
        text(value, "backupArchivePath", canonical(backupArchivePath));
        text(value, "eventsPath", canonical(eventsPath));
        text(value, "logPath", canonical(logPath));
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("backup_spec_directory_failed");
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(value.toString().getBytes(StandardCharsets.US_ASCII));
            output.flush();
            output.getFD().sync();
        }
        if (file.exists() && !file.delete()) throw new IOException("backup_spec_replace_failed");
        if (!temporary.renameTo(file)) throw new IOException("backup_spec_publish_failed");
    }

    private static void text(StringBuilder output, String key, String value) {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 ||
            value.indexOf('=') >= 0) {
            throw new IllegalArgumentException("invalid backup spec value: " + key);
        }
        output.append(key).append('=').append(value).append('\n');
    }

    private static String canonical(File file) throws IOException {
        if (file == null) throw new IOException("backup_spec_path_missing");
        return file.getCanonicalPath();
    }
}
