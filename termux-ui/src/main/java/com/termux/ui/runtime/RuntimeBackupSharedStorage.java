package com.termux.ui.runtime;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure path and size helpers for runtime backups that live directly on shared storage.
 *
 * <p>Termux reaches shared storage as {@code ~/storage/shared}, which is the same directory as the
 * primary external storage root. The export archive is written there by the tar session itself and
 * a restore reads the chosen archive in place, so nothing is copied through a private job
 * directory. The in-app chooser may never leave that root, which {@link #isWithin} enforces.</p>
 */
public final class RuntimeBackupSharedStorage {
    public static final String ARCHIVE_EXTENSION = ".tar";
    /** The shared RootFS (PRoot) backup is a zstd-compressed tar. */
    public static final String ROOTFS_ARCHIVE_EXTENSION = ".tar.zst";
    private static final String EXPORT_PAYLOAD_DIRECTORY = "usr/glibc";
    private static final int MAX_NAME_LENGTH = 120;
    private static final long TAR_BLOCK = 512L;
    /** Name prefix of the per-package Wine prefix template archives in the template cache. */
    private static final String PREFIX_TEMPLATE_PREFIX = "games-rootfs-base-prefix-";
    /** Trailing zero blocks plus filesystem slack that a byte-exact estimate cannot see. */
    private static final long EXPORT_MARGIN_BYTES = 64L * 1024L * 1024L;
    /** Slack for the PRoot backup, whose staging copy and archive are far larger than the Native one. */
    private static final long ROOTFS_BACKUP_MARGIN_BYTES = 256L * 1024L * 1024L;

    private RuntimeBackupSharedStorage() { }

    public static String exportFileName(RuntimeBackupType type, Date now) {
        return "termux-games-" + type.getStorageValue() + "-" + timestamp(now) + ARCHIVE_EXTENSION;
    }

    public static String rootfsExportFileName(Date now) {
        return "games-base-backup-" + timestamp(now) + ROOTFS_ARCHIVE_EXTENSION;
    }

    private static String timestamp(Date now) {
        return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(now);
    }

    /** True when {@code candidate} is {@code root} or lies below it once symlinks are resolved. */
    public static boolean isWithin(File root, File candidate) {
        try {
            String base = root.getCanonicalPath();
            String path = candidate.getCanonicalPath();
            return path.equals(base) || path.startsWith(base.endsWith("/") ? base : base + "/");
        } catch (IOException error) {
            return false;
        }
    }

    /** The folder one level up, or null when {@code current} is the root or already outside it. */
    @Nullable public static File parentWithin(File root, File current) {
        File parent = current.getParentFile();
        if (parent == null || !isWithin(root, current) || sameFile(root, current)) return null;
        return isWithin(root, parent) ? parent : null;
    }

    private static boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalFile().equals(b.getCanonicalFile());
        } catch (IOException error) {
            return false;
        }
    }

    @Nullable public static String normalizeArchiveName(@Nullable String input) {
        return normalizeArchiveName(input, ARCHIVE_EXTENSION);
    }

    /**
     * Turns what the user typed into an archive file name: trimmed, one path segment made of
     * English characters only, ending in {@code extension}. Returns null when nothing usable is left.
     */
    @Nullable public static String normalizeArchiveName(@Nullable String input, String extension) {
        if (input == null) return null;
        String name = input.trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name) || !isEnglishName(name)) return null;
        if (!name.toLowerCase(Locale.ROOT).endsWith(extension)) name += extension;
        if (name.length() == extension.length() || name.length() > MAX_NAME_LENGTH) return null;
        return name;
    }

    /**
     * File names only need to support English: A-Z a-z 0-9, space and {@code . _ - + = ( ) ,}.
     * That also rules out path separators, NUL and every non-ASCII character.
     */
    static boolean isEnglishName(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == ' ' || c == '.' || c == '_' || c == '-' || c == '+' || c == '=' || c == '('
                || c == ')' || c == ',';
            if (!ok) return false;
        }
        return true;
    }

    public static List<File> listChoices(File root, File directory, boolean foldersOnly) {
        return listChoices(root, directory, foldersOnly, ARCHIVE_EXTENSION);
    }

    /**
     * What the chooser shows for {@code directory}: sub-folders first, then (unless
     * {@code foldersOnly}) files ending in {@code extension}, each group sorted by name. Hidden
     * entries and anything that resolves outside {@code root} (for example through a symlink)
     * are skipped.
     */
    public static List<File> listChoices(File root, File directory, boolean foldersOnly,
                                         String extension) {
        List<File> folders = new ArrayList<>();
        List<File> archives = new ArrayList<>();
        File[] children = isWithin(root, directory) ? directory.listFiles() : null;
        if (children == null) return folders;
        for (File child : children) {
            if (child.getName().startsWith(".") || !isWithin(root, child)) continue;
            if (child.isDirectory()) {
                folders.add(child);
            } else if (!foldersOnly && child.isFile()
                && child.getName().toLowerCase(Locale.ROOT).endsWith(extension)) {
                archives.add(child);
            }
        }
        Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
        Collections.sort(folders, byName);
        Collections.sort(archives, byName);
        List<File> all = new ArrayList<>(folders);
        all.addAll(archives);
        return all;
    }

    /**
     * Bytes a tar of the export occupies: the manifest and end-marker members (a header and one
     * data block each) plus {@code usr/glibc} as 512-byte headers and 512-aligned file data.
     */
    public static long estimateExportBytes(File filesDirectory) {
        long total = 2L * (TAR_BLOCK + TAR_BLOCK); // runtime-backup.properties and runtime-backup.end
        return total + estimateTree(new File(filesDirectory, EXPORT_PAYLOAD_DIRECTORY));
    }

    private static long estimateTree(File file) {
        long total = TAR_BLOCK; // header for this entry
        if (isSymbolicLink(file)) return total;
        if (file.isFile()) return total + roundUp(file.length());
        File[] children = file.listFiles();
        if (children == null) return total;
        for (File child : children) total += estimateTree(child);
        return total;
    }

    /** tar stores a symlink as a header only; java.nio.file.Files needs API 26, minSdk is 21. */
    private static boolean isSymbolicLink(File file) {
        try {
            File parent = file.getParentFile();
            File resolved = parent == null ? file : new File(parent.getCanonicalFile(), file.getName());
            return !resolved.getCanonicalFile().equals(resolved.getAbsoluteFile());
        } catch (IOException error) {
            return false;
        }
    }

    private static long roundUp(long length) {
        return (length + TAR_BLOCK - 1L) / TAR_BLOCK * TAR_BLOCK;
    }

    /**
     * Bytes the PRoot backup has to copy: the shared RootFS container tree plus the top-level
     * files of the template cache (the archives and the recipe). Sub-directories of the cache,
     * such as a leftover {@code backup-staging}, are not part of what is copied.
     */
    public static long estimateRootfsBackupBytes(File containerDirectory, File templateCacheDirectory) {
        return estimateRootfsBackupBytes(containerDirectory, templateCacheDirectory, true);
    }

    /** As above; with {@code includePrefixTemplates=false} the Wine prefix template archives are not counted. */
    public static long estimateRootfsBackupBytes(File containerDirectory, File templateCacheDirectory,
                                                 boolean includePrefixTemplates) {
        long total = estimateTree(containerDirectory);
        File[] files = templateCacheDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) continue;
                if (!includePrefixTemplates && file.getName().startsWith(PREFIX_TEMPLATE_PREFIX)) continue;
                total += TAR_BLOCK + roundUp(file.length());
            }
        }
        return total;
    }

    /**
     * The PRoot backup first copies the whole tree into a staging area on the private partition
     * and then compresses it into the chosen file. The compressed size is conservatively taken as
     * half of the tree, and because the private partition and shared storage are often one pool,
     * the private check covers the staging copy and the archive together.
     *
     * @throws InsufficientSpaceException when the private partition or the destination is too small.
     */
    public static void requireRootfsBackupSpace(long treeBytes, long privateAvailableBytes,
                                                long destinationAvailableBytes) throws IOException {
        long archive = treeBytes / 2L;
        long privateNeeded = treeBytes + archive + ROOTFS_BACKUP_MARGIN_BYTES;
        if (privateAvailableBytes < privateNeeded) {
            throw new InsufficientSpaceException(privateNeeded, privateAvailableBytes);
        }
        long destinationNeeded = archive + ROOTFS_BACKUP_MARGIN_BYTES;
        if (destinationAvailableBytes < destinationNeeded) {
            throw new InsufficientSpaceException(destinationNeeded, destinationAvailableBytes);
        }
    }

    /** @throws InsufficientSpaceException when {@code availableBytes} cannot hold the export. */
    public static void requireExportSpace(long estimatedBytes, long availableBytes) throws IOException {
        long required = estimatedBytes + EXPORT_MARGIN_BYTES;
        if (availableBytes < required) throw new InsufficientSpaceException(required, availableBytes);
    }

    public static final class InsufficientSpaceException extends IOException {
        private final long requiredBytes;
        private final long availableBytes;

        InsufficientSpaceException(long requiredBytes, long availableBytes) {
            super("runtime_backup_insufficient_space");
            this.requiredBytes = requiredBytes;
            this.availableBytes = availableBytes;
        }

        public long getRequiredBytes() { return requiredBytes; }
        public long getAvailableBytes() { return availableBytes; }
    }
}
