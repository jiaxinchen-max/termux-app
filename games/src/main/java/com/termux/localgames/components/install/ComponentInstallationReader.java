package com.termux.localgames.components.install;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Strict read-only projection of installer metadata. */
public final class ComponentInstallationReader {

    private static final String RECEIPT = ".games-component.properties";

    private final File installRoot;

    public ComponentInstallationReader(File installRoot) {
        if (installRoot == null) throw new IllegalArgumentException("installRoot must not be null");
        this.installRoot = installRoot;
    }

    public ComponentInstallationSnapshot read(String packageName) throws IOException {
        requirePackageName(packageName);
        File packageRoot = new File(installRoot, packageName);
        File pointerFile = new File(packageRoot, "active.properties");
        if (!pointerFile.isFile()) {
            File backup = new File(pointerFile.getPath() + ".bak");
            if (backup.isFile()) pointerFile = backup;
            else return new ComponentInstallationSnapshot(null, null);
        }

        Properties pointer = readProperties(pointerFile);
        if (!"1".equals(pointer.getProperty("schemaVersion"))) {
            throw invalid("Unsupported active pointer schema");
        }
        InstalledComponent active = readVersion(packageRoot, packageName,
            safeDirectory(pointer.getProperty("active", "")));
        InstalledComponent previous = readVersion(packageRoot, packageName,
            safeDirectory(pointer.getProperty("previous", "")));
        if (active == null && previous != null) {
            throw invalid("Previous version cannot exist without active version");
        }
        return new ComponentInstallationSnapshot(active, previous);
    }

    private static InstalledComponent readVersion(File packageRoot, String packageName,
                                                  String directoryName) throws IOException {
        if (directoryName.isEmpty()) return null;
        File directory = new File(new File(packageRoot, "versions"), directoryName);
        Properties receipt = readProperties(new File(directory, RECEIPT));
        if (!"1".equals(receipt.getProperty("schemaVersion"))) {
            throw invalid("Unsupported component receipt schema");
        }
        String receiptPackage = required(receipt, "packageName");
        String sha256 = required(receipt, "sha256");
        int version;
        try {
            version = Integer.parseInt(required(receipt, "version"));
        } catch (NumberFormatException error) {
            throw invalid("Invalid component receipt version", error);
        }
        if (!directory.isDirectory() || !packageName.equals(receiptPackage) || version < 1 ||
            !sha256.matches("[0-9a-f]{64}")) {
            throw invalid("Component receipt does not match installation directory");
        }
        return new InstalledComponent(receiptPackage, version, sha256, directory);
    }

    private static String safeDirectory(String value) throws IOException {
        if (value.isEmpty()) return value;
        if (!value.matches("[A-Za-z0-9._-]+")) throw invalid("Invalid version directory");
        return value;
    }

    private static void requirePackageName(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid packageName");
        }
    }

    private static Properties readProperties(File file) throws IOException {
        if (!file.isFile()) throw invalid("Missing component installation metadata");
        Properties properties = new Properties();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            properties.load(input);
        }
        return properties;
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw invalid("Missing " + key);
        return value;
    }

    private static ComponentInstallException invalid(String message) {
        return new ComponentInstallException("invalid_install_metadata", message);
    }

    private static ComponentInstallException invalid(String message, Throwable cause) {
        return new ComponentInstallException("invalid_install_metadata", message, cause);
    }
}
