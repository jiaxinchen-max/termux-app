package com.termux.localgames.runtime;

import com.termux.localgames.data.GameStoragePaths;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Atomic active/previous swap for already validated Termux-built runtimes. */
public final class RootfsRuntimeActivationStore {
    private final GameStoragePaths paths;

    public RootfsRuntimeActivationStore(GameStoragePaths paths) {
        if (paths == null) throw new IllegalArgumentException("paths required");
        this.paths = paths;
    }

    public synchronized RootfsRuntimeInstallation rollback(String packageName) throws IOException {
        RootfsRuntimeInstallationReader reader = new RootfsRuntimeInstallationReader(paths);
        reader.readActive(packageName).orElseThrow(() -> new IOException("rootfs_active_missing"));
        reader.readPrevious(packageName).orElseThrow(() -> new IOException("rootfs_rollback_unavailable"));
        File packageDirectory = new File(paths.getRootfsRuntimeDirectory(), packageName);
        File pointerFile = new File(packageDirectory, "active.properties");
        Properties pointer = read(pointerFile);
        if (!"1".equals(pointer.getProperty("schemaVersion"))) {
            throw new IOException("rootfs_active_pointer_unsupported");
        }
        String active = id(pointer.getProperty("active"));
        String previous = id(pointer.getProperty("previous"));
        Properties swapped = new Properties();
        swapped.setProperty("schemaVersion", "1");
        swapped.setProperty("active", previous);
        swapped.setProperty("previous", active);
        writeAtomic(pointerFile, swapped);
        return reader.readActive(packageName)
            .orElseThrow(() -> new IOException("rootfs_rollback_activation_failed"));
    }

    private static Properties read(File file) throws IOException {
        if (!file.isFile()) throw new IOException("rootfs_active_pointer_missing");
        Properties value = new Properties();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            value.load(input);
        }
        return value;
    }

    private static void writeAtomic(File target, Properties value) throws IOException {
        File temporary = new File(target.getPath() + ".tmp");
        File backup = new File(target.getPath() + ".bak");
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            value.store(output, "Games RootFS activation");
            output.flush();
            output.getFD().sync();
        }
        if (backup.exists() && !backup.delete()) throw new IOException("rootfs_pointer_backup_failed");
        if (!target.renameTo(backup)) throw new IOException("rootfs_pointer_stage_failed");
        if (!temporary.renameTo(target)) {
            backup.renameTo(target);
            throw new IOException("rootfs_pointer_publish_failed");
        }
        if (!backup.delete()) throw new IOException("rootfs_pointer_backup_cleanup_failed");
    }

    private static String id(String value) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("rootfs_pointer_invalid");
        }
        return value;
    }
}
