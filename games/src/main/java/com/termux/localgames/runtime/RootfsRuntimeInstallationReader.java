package com.termux.localgames.runtime;

import com.termux.localgames.data.GameStoragePaths;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/** Reads Games activation metadata while keeping the RootFS inside Termux-owned storage. */
public final class RootfsRuntimeInstallationReader {
    private final GameStoragePaths paths;

    public RootfsRuntimeInstallationReader(GameStoragePaths paths) {
        if (paths == null) throw new IllegalArgumentException("paths required");
        this.paths = paths;
    }

    public Optional<RootfsRuntimeInstallation> readActive(String packageName) throws IOException {
        return readSelected(packageName, "active");
    }

    public Optional<RootfsRuntimeInstallation> readPrevious(String packageName) throws IOException {
        return readSelected(packageName, "previous");
    }

    private Optional<RootfsRuntimeInstallation> readSelected(String packageName, String key)
        throws IOException {
        requireId(packageName, "packageName");
        File packageDirectory = new File(paths.getRootfsRuntimeDirectory(), packageName);
        File pointerFile = recoverableFile(new File(packageDirectory, "active.properties"));
        if (pointerFile == null) return Optional.empty();
        Properties pointer = read(pointerFile);
        requireValue(pointer, "schemaVersion", "1", "rootfs_active_pointer_unsupported");
        String selected = pointer.getProperty(key, "").trim();
        if (selected.isEmpty() && "previous".equals(key)) return Optional.empty();
        selected = requireId(selected, key);
        File receiptFile = new File(new File(packageDirectory, "versions"), selected + ".properties");
        Properties receipt = read(receiptFile);
        requireValue(receipt, "schemaVersion", "1", "rootfs_receipt_unsupported");
        requireValue(receipt, "packageName", packageName, "rootfs_receipt_package_mismatch");
        String containerName = requireId(required(receipt, "containerName"), "containerName");
        String recipeSha256 = required(receipt, "recipeSha256");
        if (!recipeSha256.matches("[0-9a-f]{64}")) throw new IOException("rootfs_recipe_digest_invalid");
        int version;
        try { version = Integer.parseInt(required(receipt, "version")); }
        catch (NumberFormatException error) { throw new IOException("rootfs_version_invalid", error); }
        if (version < 1) throw new IOException("rootfs_version_invalid");

        File containers = paths.getProotDistroContainersDirectory().getCanonicalFile();
        File container = new File(containers, containerName).getCanonicalFile();
        requireContained(containers, container, "rootfs_container_outside_termux_runtime");
        File rootfs = new File(container, "rootfs").getCanonicalFile();
        requireContained(container, rootfs, "rootfs_path_outside_container");
        if (!rootfs.isDirectory()) throw new IOException("rootfs_content_missing");
        return Optional.of(new RootfsRuntimeInstallation(packageName, version, recipeSha256,
            containerName, rootfs));
    }

    private static File recoverableFile(File target) throws IOException {
        if (target.isFile()) return target;
        File backup = new File(target.getPath() + ".bak");
        if (backup.isFile()) return backup;
        return null;
    }

    private static Properties read(File file) throws IOException {
        if (!file.isFile()) throw new IOException("rootfs_metadata_missing");
        Properties properties = new Properties();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            properties.load(input);
        }
        return properties;
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("rootfs_metadata_missing:" + key);
        return value.trim();
    }

    private static String requireId(String value, String field) throws IOException {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IOException("rootfs_metadata_invalid:" + field);
        }
        return value;
    }

    private static void requireValue(Properties properties, String key, String expected,
                                     String error) throws IOException {
        if (!expected.equals(properties.getProperty(key))) throw new IOException(error);
    }

    private static void requireContained(File parent, File child, String error) throws IOException {
        if (!(child.equals(parent) || child.getPath().startsWith(parent.getPath() + File.separator))) {
            throw new IOException(error);
        }
    }
}
