package com.termux.localgames.components.install;

import com.termux.localgames.components.DownloadControl;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class SafeTarXzExtractor {

    static final class Result {
        final int entries;
        final long bytes;

        Result(int entries, long bytes) {
            this.entries = entries;
            this.bytes = bytes;
        }
    }

    private final ArchiveFileOperations fileOperations;
    private final int maxEntries;
    private final long maxBytes;
    private final List<String> allowedAbsoluteLinkPrefixes;

    SafeTarXzExtractor(ArchiveFileOperations fileOperations, int maxEntries, long maxBytes) {
        this(fileOperations, maxEntries, maxBytes, java.util.Collections.emptyList());
    }

    /**
     * @param allowedAbsoluteLinkPrefixes absolute prefixes a symbolic link may point at. Empty
     *     means every absolute target is rejected. Runtime packages built for a fixed install
     *     location (the GLIBC prefix) ship absolute links that only resolve once installed there,
     *     so the caller opts in per package instead of relaxing this for every archive.
     */
    SafeTarXzExtractor(ArchiveFileOperations fileOperations, int maxEntries, long maxBytes,
                       List<String> allowedAbsoluteLinkPrefixes) {
        this.fileOperations = fileOperations;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.allowedAbsoluteLinkPrefixes = allowedAbsoluteLinkPrefixes == null
            ? java.util.Collections.emptyList()
            : java.util.Collections.unmodifiableList(
                new ArrayList<>(allowedAbsoluteLinkPrefixes));
    }

    Result extract(File archive, File destination, DownloadControl control) throws IOException {
        String root = destination.getCanonicalPath() + File.separator;
        int entries = 0;
        long totalBytes = 0;
        Set<String> seenPaths = new HashSet<>();
        List<DirectoryMode> directoryModes = new ArrayList<>();
        try (BufferedInputStream fileInput = new BufferedInputStream(new FileInputStream(archive));
             InputStream payloadInput = openPayload(fileInput);
             TarArchiveInputStream tarInput = new TarArchiveInputStream(payloadInput)) {
            TarArchiveEntry entry;
            byte[] buffer = new byte[32 * 1024];
            while ((entry = tarInput.getNextTarEntry()) != null) {
                checkControl(control);
                entries++;
                if (entries > maxEntries) throw failure("archive_limit", "Too many archive entries");
                File output = safeEntry(destination, root, entry.getName());
                ensureNoSymlinkAncestor(destination, output);
                String outputPath = output.getCanonicalPath();
                if (!seenPaths.add(outputPath) && !entry.isDirectory()) {
                    throw failure("duplicate_entry", "Duplicate archive entry");
                }
                if (output.getCanonicalPath().equals(new File(destination,
                    ".games-component.properties").getCanonicalPath())) {
                    throw failure("reserved_entry", "Archive uses reserved receipt path");
                }
                if (entry.isDirectory()) {
                    ensureDirectory(output);
                    if (!output.getCanonicalPath().equals(destination.getCanonicalPath())) {
                        directoryModes.add(new DirectoryMode(output, entry.getMode()));
                    }
                } else if (entry.isSymbolicLink()) {
                    ensureParent(output);
                    validateLinkTarget(output.getParentFile(), root, entry.getLinkName());
                    fileOperations.createSymbolicLink(entry.getLinkName(), output);
                } else if (entry.isLink()) {
                    ensureParent(output);
                    File target = safeEntry(destination, root, entry.getLinkName());
                    if (!target.isFile()) {
                        throw failure("invalid_hard_link", "Hard-link target is unavailable");
                    }
                    fileOperations.createHardLink(target, output);
                } else if (entry.isFile()) {
                    long size = entry.getSize();
                    if (size < 0 || size > maxBytes - totalBytes) {
                        throw failure("archive_limit", "Expanded archive exceeds byte limit");
                    }
                    ensureParent(output);
                    long written = 0;
                    try (OutputStream stream = new BufferedOutputStream(
                        new FileOutputStream(output, false))) {
                        while (written < size) {
                            checkControl(control);
                            int count = tarInput.read(buffer, 0,
                                (int) Math.min(buffer.length, size - written));
                            if (count < 0) throw failure("truncated_archive", "Archive entry is truncated");
                            stream.write(buffer, 0, count);
                            written += count;
                        }
                    }
                    totalBytes += written;
                    fileOperations.applyMode(output, entry.getMode());
                } else {
                    throw failure("unsupported_entry", "Unsupported archive entry type");
                }
            }
            for (int i = directoryModes.size() - 1; i >= 0; i--) {
                DirectoryMode mode = directoryModes.get(i);
                fileOperations.applyMode(mode.file, mode.mode);
            }
        } catch (ComponentInstallException e) {
            throw e;
        } catch (IOException e) {
            throw new ComponentInstallException("extract_failed", "Unable to extract component", e);
        }
        if (entries == 0) throw failure("empty_archive", "Component archive is empty");
        return new Result(entries, totalBytes);
    }

    /** Source components may be upstream plain tar files; packaged components remain tar.xz. */
    private static InputStream openPayload(BufferedInputStream input) throws IOException {
        input.mark(6);
        byte[] magic = new byte[6];
        int count = input.read(magic);
        input.reset();
        boolean xz = count == 6 &&
            (magic[0] & 0xff) == 0xfd && magic[1] == 0x37 && magic[2] == 0x7a &&
            magic[3] == 0x58 && magic[4] == 0x5a && magic[5] == 0x00;
        return xz ? new XZInputStream(input) : input;
    }

    private static File safeEntry(File destination, String root, String name) throws IOException {
        if (name == null || name.isEmpty() || new File(name).isAbsolute()) {
            throw failure("path_traversal", "Invalid archive path");
        }
        File output = new File(destination, name);
        String path = output.getCanonicalPath();
        String rootDirectory = root.substring(0, root.length() - File.separator.length());
        if (!path.equals(rootDirectory) && !path.startsWith(root)) {
            throw failure("path_traversal", "Archive path escapes staging");
        }
        return output;
    }

    private void ensureNoSymlinkAncestor(File destination, File output) throws IOException {
        File current = output.getParentFile();
        String root = destination.getAbsolutePath();
        while (current != null && !current.getAbsolutePath().equals(root)) {
            if (fileOperations.isSymbolicLink(current)) {
                throw failure("link_traversal", "Archive entry traverses a symbolic link");
            }
            current = current.getParentFile();
        }
        if (current == null) throw failure("path_traversal", "Archive path escapes staging");
    }

    private void validateLinkTarget(File parent, String root, String target)
        throws IOException {
        if (target == null || target.isEmpty()) {
            throw failure("link_traversal", "Invalid symbolic-link target");
        }
        if (new File(target).isAbsolute()) {
            for (String prefix : allowedAbsoluteLinkPrefixes) {
                if (target.startsWith(prefix)) return;
            }
            throw failure("link_traversal", "Invalid symbolic-link target");
        }
        String path = new File(parent, target).getCanonicalPath();
        if (!path.startsWith(root)) throw failure("link_traversal", "Link escapes staging");
    }

    private static void ensureParent(File file) throws IOException {
        ensureDirectory(file.getParentFile());
    }

    private static void ensureDirectory(File file) throws IOException {
        if (file == null || ((!file.isDirectory() && !file.mkdirs()) || !file.isDirectory())) {
            throw failure("storage_error", "Unable to create staging directory");
        }
    }

    private static ComponentInstallException failure(String code, String message) {
        return new ComponentInstallException(code, message);
    }

    private static void checkControl(DownloadControl control) throws InstallInterruptedException {
        DownloadControl.Decision decision = control.currentDecision();
        if (decision == DownloadControl.Decision.PAUSE ||
            decision == DownloadControl.Decision.CANCEL) {
            throw new InstallInterruptedException(decision);
        }
    }

    private static final class DirectoryMode {
        final File file;
        final int mode;

        DirectoryMode(File file, int mode) {
            this.file = file;
            this.mode = mode;
        }
    }
}
