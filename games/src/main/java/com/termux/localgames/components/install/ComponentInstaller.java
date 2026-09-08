package com.termux.localgames.components.install;

import com.termux.localgames.components.DownloadControl;
import com.termux.localgames.data.ComponentTaskRepository;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;

/** Transactional installer for verified component archives. */
public final class ComponentInstaller {

    private static final String RECEIPT = ".games-component.properties";
    private static final int DEFAULT_MAX_ENTRIES = 100_000;
    private static final long DEFAULT_MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024;

    private final ComponentTaskRepository repository;
    private final File installRoot;
    private final ArchiveFileOperations fileOperations;
    private final SafeTarXzExtractor extractor;
    private RuntimeComponentActivator activator = RuntimeComponentActivator.NONE;

    /** Installs components into the launcher-visible runtime after each version is published. */
    public void setRuntimeComponentActivator(RuntimeComponentActivator value) {
        this.activator = value == null ? RuntimeComponentActivator.NONE : value;
    }

    public ComponentInstaller(ComponentTaskRepository repository, File installRoot,
                              ArchiveFileOperations fileOperations) throws IOException {
        this(repository, installRoot, fileOperations, DEFAULT_MAX_ENTRIES,
            DEFAULT_MAX_EXPANDED_BYTES, java.util.Collections.emptyList());
    }

    /**
     * @param allowedAbsoluteLinkPrefixes absolute prefixes symbolic links may point at; empty
     *     rejects every absolute target. Runtime packages built for a fixed install location
     *     ship links that only resolve once installed there.
     */
    public ComponentInstaller(ComponentTaskRepository repository, File installRoot,
                              ArchiveFileOperations fileOperations,
                              java.util.List<String> allowedAbsoluteLinkPrefixes)
        throws IOException {
        this(repository, installRoot, fileOperations, DEFAULT_MAX_ENTRIES,
            DEFAULT_MAX_EXPANDED_BYTES, allowedAbsoluteLinkPrefixes);
    }

    ComponentInstaller(ComponentTaskRepository repository, File installRoot,
                       ArchiveFileOperations fileOperations, int maxEntries,
                       long maxExpandedBytes) throws IOException {
        this(repository, installRoot, fileOperations, maxEntries, maxExpandedBytes,
            java.util.Collections.emptyList());
    }

    ComponentInstaller(ComponentTaskRepository repository, File installRoot,
                       ArchiveFileOperations fileOperations, int maxEntries,
                       long maxExpandedBytes,
                       java.util.List<String> allowedAbsoluteLinkPrefixes) throws IOException {
        if (repository == null || installRoot == null || fileOperations == null) {
            throw new IllegalArgumentException("installer dependencies must not be null");
        }
        this.repository = repository;
        this.installRoot = installRoot;
        this.fileOperations = fileOperations;
        this.extractor = new SafeTarXzExtractor(fileOperations, maxEntries, maxExpandedBytes,
            allowedAbsoluteLinkPrefixes);
        ensureDirectory(installRoot);
        ensureDirectory(stagingRoot());
    }

    public synchronized InstalledComponent install(ComponentTask initialTask, File archive,
                                                   ComponentInstallListener listener)
        throws IOException {
        return install(initialTask, archive, DownloadControl.CONTINUE, listener);
    }

    public synchronized InstalledComponent install(ComponentTask initialTask, File archive,
                                                   DownloadControl control,
                                                   ComponentInstallListener listener)
        throws IOException {
        if (initialTask == null || archive == null || control == null || listener == null) {
            throw new IllegalArgumentException("install arguments must not be null");
        }
        if (initialTask.getState() == ComponentTaskState.CANCELLED) {
            throw new ComponentInstallException("task_cancelled", "Cancelled task cannot install");
        }
        Optional<InstalledComponent> current = findActive(initialTask.getPackageName());
        if (initialTask.getState() == ComponentTaskState.INSTALLED && current.isPresent() &&
            matches(current.get(), initialTask)) {
            return current.get();
        }

        ComponentTask task = initialTask;
        File staging = new File(stagingRoot(), task.getTaskId());
        boolean activated = false;
        try {
            verifyArchive(archive, task);
            task = transition(task, ComponentTaskState.INSTALLING, "", "", listener);
            deleteTree(staging);
            ensureDirectory(staging);

            File packageRoot = packageRoot(task.getPackageName());
            File versions = new File(packageRoot, "versions");
            ensureDirectory(versions);
            String versionDirectoryName = versionDirectoryName(task);
            File versionDirectory = new File(versions, versionDirectoryName);
            if (versionDirectory.exists()) {
                if (!validReceipt(versionDirectory, task)) {
                    throw new ComponentInstallException("version_directory_conflict",
                        "Existing immutable version directory is invalid");
                }
                deleteTree(staging);
            } else {
                SafeTarXzExtractor.Result result = extractor.extract(archive, staging, control);
                writeReceipt(staging, task, result);
                checkControl(control);
                if (!staging.renameTo(versionDirectory)) {
                    throw new ComponentInstallException("publish_failed",
                        "Unable to publish staged component version");
                }
            }

            // Publish into the launcher-visible runtime before the active pointer moves.
            // The catalog reports INSTALLED purely from that pointer, so activating first keeps
            // a component that never reached the launcher from showing up as installed.
            // `activated` also stays false until this succeeds, so the task still fails.
            activator.activate(task, versionDirectory);

            ActivePointer pointer = readPointer(packageRoot);
            checkControl(control);
            if (!versionDirectoryName.equals(pointer.activeDirectory)) {
                writePointer(packageRoot, new ActivePointer(versionDirectoryName,
                    pointer.activeDirectory));
            }
            activated = true;
            task = transition(task, ComponentTaskState.INSTALLED, "", "", listener);
            return installed(versionDirectory, task);
        } catch (IOException error) {
            try {
                deleteTree(staging);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            if (!activated && !task.getState().isTerminal()) {
                ComponentTaskState failedState = ComponentTaskState.FAILED;
                if (error instanceof InstallInterruptedException) {
                    failedState = ((InstallInterruptedException) error).getDecision() ==
                        DownloadControl.Decision.PAUSE
                        ? ComponentTaskState.PAUSED : ComponentTaskState.CANCELLED;
                }
                String code = error instanceof ComponentInstallException
                    ? ((ComponentInstallException) error).getErrorCode() : "install_io_error";
                try {
                    transition(task, failedState,
                        failedState == ComponentTaskState.FAILED ? code : "",
                        failedState == ComponentTaskState.FAILED ? safeMessage(error) : "",
                        listener);
                } catch (IOException persistenceError) {
                    error.addSuppressed(persistenceError);
                }
            }
            throw error;
        }
    }

    public synchronized Optional<InstalledComponent> findActive(String packageName)
        throws IOException {
        File packageRoot = packageRoot(packageName);
        ActivePointer pointer = readPointer(packageRoot);
        if (pointer.activeDirectory.isEmpty()) return Optional.empty();
        File directory = new File(new File(packageRoot, "versions"), pointer.activeDirectory);
        InstalledComponent component = readReceipt(directory);
        requirePackage(component, packageName);
        return Optional.of(component);
    }

    public synchronized InstalledComponent rollback(String packageName) throws IOException {
        File packageRoot = packageRoot(packageName);
        ActivePointer pointer = readPointer(packageRoot);
        if (pointer.previousDirectory.isEmpty()) {
            throw new ComponentInstallException("rollback_unavailable", "No previous component version");
        }
        File previous = new File(new File(packageRoot, "versions"), pointer.previousDirectory);
        InstalledComponent component = readReceipt(previous);
        requirePackage(component, packageName);
        writePointer(packageRoot, new ActivePointer(pointer.previousDirectory,
            pointer.activeDirectory));
        return component;
    }

    private ComponentTask transition(ComponentTask task, ComponentTaskState state,
                                     String errorCode, String errorMessage,
                                     ComponentInstallListener listener) throws IOException {
        ComponentTask updated = task.transition(state, task.getExpectedSize(), task.getEtag(),
            task.getLastModified(), errorCode, errorMessage);
        repository.save(updated);
        try {
            listener.onTaskUpdated(updated);
        } catch (RuntimeException ignored) {
            // Notification/UI observers cannot change the persisted install outcome.
        }
        return updated;
    }

    private static void verifyArchive(File archive, ComponentTask task) throws IOException {
        if (!archive.isFile() || archive.length() != task.getExpectedSize()) {
            throw new ComponentInstallException("archive_size_mismatch",
                "Verified component archive size changed");
        }
        if (!task.getSha256().equals(sha256(archive))) {
            throw new ComponentInstallException("archive_sha256_mismatch",
                "Verified component archive digest changed");
        }
    }

    private void writeReceipt(File directory, ComponentTask task,
                              SafeTarXzExtractor.Result result) throws IOException {
        Properties receipt = new Properties();
        receipt.setProperty("schemaVersion", "1");
        receipt.setProperty("packageName", task.getPackageName());
        receipt.setProperty("version", String.valueOf(task.getVersion()));
        receipt.setProperty("sha256", task.getSha256());
        receipt.setProperty("entries", String.valueOf(result.entries));
        receipt.setProperty("expandedBytes", String.valueOf(result.bytes));
        writeProperties(new File(directory, RECEIPT), receipt);
    }

    private boolean validReceipt(File directory, ComponentTask task) {
        try {
            return matches(readReceipt(directory), task);
        } catch (IOException e) {
            return false;
        }
    }

    private InstalledComponent readReceipt(File directory) throws IOException {
        Properties receipt = readProperties(new File(directory, RECEIPT));
        if (!"1".equals(receipt.getProperty("schemaVersion"))) {
            throw new ComponentInstallException("invalid_receipt", "Unsupported install receipt");
        }
        try {
            String packageName = required(receipt, "packageName");
            int version = Integer.parseInt(required(receipt, "version"));
            String sha256 = required(receipt, "sha256");
            if (!directory.isDirectory() || !packageName.matches("[A-Za-z0-9._-]+") ||
                version < 1 || !sha256.matches("[0-9a-f]{64}")) {
                throw new ComponentInstallException("invalid_receipt", "Invalid install receipt");
            }
            return new InstalledComponent(packageName, version, sha256, directory);
        } catch (NumberFormatException e) {
            throw new ComponentInstallException("invalid_receipt", "Invalid install receipt", e);
        }
    }

    private static InstalledComponent installed(File directory, ComponentTask task) {
        return new InstalledComponent(task.getPackageName(), task.getVersion(),
            task.getSha256(), directory);
    }

    private static boolean matches(InstalledComponent component, ComponentTask task) {
        return component.getPackageName().equals(task.getPackageName()) &&
            component.getVersion() == task.getVersion() &&
            component.getSha256().equals(task.getSha256());
    }

    private static void requirePackage(InstalledComponent component, String packageName)
        throws ComponentInstallException {
        if (!packageName.equals(component.getPackageName())) {
            throw new ComponentInstallException("invalid_receipt",
                "Install receipt does not match package directory");
        }
    }

    private ActivePointer readPointer(File packageRoot) throws IOException {
        File pointerFile = new File(packageRoot, "active.properties");
        recoverBackup(pointerFile);
        if (!pointerFile.isFile()) return new ActivePointer("", "");
        Properties value = readProperties(pointerFile);
        if (!"1".equals(value.getProperty("schemaVersion"))) {
            throw new ComponentInstallException("invalid_active_pointer",
                "Unsupported active pointer schema");
        }
        return new ActivePointer(safeDirectory(value.getProperty("active", "")),
            safeDirectory(value.getProperty("previous", "")));
    }

    private void writePointer(File packageRoot, ActivePointer pointer) throws IOException {
        ensureDirectory(packageRoot);
        Properties value = new Properties();
        value.setProperty("schemaVersion", "1");
        value.setProperty("active", pointer.activeDirectory);
        value.setProperty("previous", pointer.previousDirectory);
        writePropertiesAtomic(new File(packageRoot, "active.properties"), value);
    }

    private static void writePropertiesAtomic(File target, Properties properties)
        throws IOException {
        File temporary = new File(target.getPath() + ".tmp");
        File backup = new File(target.getPath() + ".bak");
        deleteFile(temporary);
        writeProperties(temporary, properties);
        deleteFile(backup);
        boolean hadTarget = target.isFile();
        if (hadTarget && !target.renameTo(backup)) {
            throw new IOException("Unable to stage active pointer");
        }
        if (!temporary.renameTo(target)) {
            if (hadTarget) backup.renameTo(target);
            throw new IOException("Unable to replace active pointer");
        }
        deleteFile(backup);
    }

    private static void recoverBackup(File target) throws IOException {
        File backup = new File(target.getPath() + ".bak");
        if (!target.exists() && backup.isFile() && !backup.renameTo(target)) {
            throw new IOException("Unable to recover active pointer");
        }
    }

    private static void writeProperties(File file, Properties properties) throws IOException {
        ensureDirectory(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            properties.store(output, "Games component install metadata");
            output.flush();
            output.getFD().sync();
        }
    }

    private static Properties readProperties(File file) throws IOException {
        if (!file.isFile()) throw new IOException("Missing metadata: " + file.getName());
        Properties value = new Properties();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            value.load(input);
        }
        return value;
    }

    private File packageRoot(String packageName) {
        if (packageName == null || !packageName.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("invalid packageName");
        }
        return new File(installRoot, packageName);
    }

    private File stagingRoot() {
        return new File(installRoot, ".staging");
    }

    private static String versionDirectoryName(ComponentTask task) {
        return "v" + task.getVersion() + "-" + task.getSha256().substring(0, 12);
    }

    private static String safeDirectory(String value) throws IOException {
        if (value.isEmpty()) return value;
        if (!value.matches("[A-Za-z0-9._-]+")) {
            throw new ComponentInstallException("invalid_active_pointer",
                "Invalid version directory in active pointer");
        }
        return value;
    }

    private void deleteTree(File file) throws IOException {
        if (!file.exists() && !fileOperations.isSymbolicLink(file)) return;
        if (file.isDirectory() && !fileOperations.isSymbolicLink(file)) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Unable to list " + file.getName());
            for (File child : children) deleteTree(child);
        }
        if ((file.exists() || fileOperations.isSymbolicLink(file)) && !file.delete()) {
            throw new IOException("Unable to delete " + file.getName());
        }
    }

    private static void deleteFile(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Unable to delete " + file.getName());
    }

    private static void ensureDirectory(File file) throws IOException {
        if (file == null || ((!file.isDirectory() && !file.mkdirs()) || !file.isDirectory())) {
            throw new IOException("Unable to create directory");
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IOException("Missing " + key);
        return value;
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder value = new StringBuilder(64);
        for (byte item : digest.digest()) {
            value.append(String.format(Locale.US, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static String safeMessage(IOException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static void checkControl(DownloadControl control) throws InstallInterruptedException {
        DownloadControl.Decision decision = control.currentDecision();
        if (decision == DownloadControl.Decision.PAUSE ||
            decision == DownloadControl.Decision.CANCEL) {
            throw new InstallInterruptedException(decision);
        }
    }

    private static final class ActivePointer {
        final String activeDirectory;
        final String previousDirectory;

        ActivePointer(String activeDirectory, String previousDirectory) {
            this.activeDirectory = activeDirectory;
            this.previousDirectory = previousDirectory;
        }
    }
}
