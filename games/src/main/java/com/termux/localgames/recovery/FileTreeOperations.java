package com.termux.localgames.recovery;

import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Bounded operations for private recovery trees, including validated Wine/PRoot links. */
final class FileTreeOperations {

    static final Limits MUTATION_LIMITS = new Limits(250_000, 32L * 1024 * 1024 * 1024, 64);
    static final Limits INVENTORY_LIMITS = new Limits(250_000, Long.MAX_VALUE, 64);

    private FileTreeOperations() { }

    static Stats measure(File source, Limits limits) throws IOException {
        if (!source.exists()) return new Stats(0, 0, 0, false);
        Counter counter = new Counter(limits, false);
        File root = canonicalRoot(source);
        walkMeasure(root, root, 0, counter);
        return counter.stats();
    }

    static void copyTree(File source, File target, Limits limits) throws IOException {
        if (!source.exists()) return;
        if (target.exists()) throw new IOException("snapshot_copy_target_exists");
        File root = canonicalRoot(source);
        copy(root, root, target, 0, new Counter(limits, true));
    }

    static String checksum(File root) throws IOException {
        if (!root.isDirectory()) throw new IOException("snapshot_payload_missing");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            File canonical = canonicalRoot(root);
            digestTree(canonical, canonical, 0, new Counter(MUTATION_LIMITS, true), digest);
            StringBuilder value = new StringBuilder(64);
            for (byte part : digest.digest()) value.append(String.format("%02x", part & 0xff));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static void deleteTree(File target) throws IOException {
        if (!target.exists()) return;
        File root = canonicalRoot(target);
        delete(root, root, 0);
    }

    static void requireDirectChild(File parent, File child) throws IOException {
        File canonicalParent = parent.getCanonicalFile();
        File canonicalChild = child.getCanonicalFile();
        File expected = new File(canonicalParent, child.getName());
        if (!canonicalParent.equals(canonicalChild.getParentFile()) ||
            !canonicalChild.equals(expected)) throw new IOException("private_asset_path_escape");
    }

    private static void walkMeasure(File root, File current, int depth, Counter counter)
        throws IOException {
        StructStat stat = lstat(current);
        counter.enter(current, depth);
        if (isLink(stat)) {
            validateLink(root, current, readLink(current));
            counter.file(stat.st_size);
            return;
        }
        if (isFile(stat)) {
            counter.file(stat.st_size);
            return;
        }
        if (!isDirectory(stat)) throw new IOException("private_asset_unsupported_entry");
        counter.directory();
        for (File child : children(current)) {
            if (!counter.canContinue()) return;
            requireContainedEntry(root, child);
            walkMeasure(root, child, depth + 1, counter);
        }
    }

    private static void copy(File root, File source, File target, int depth, Counter counter)
        throws IOException {
        StructStat stat = lstat(source);
        counter.enter(source, depth);
        if (isLink(stat)) {
            String link = readLink(source);
            validateLink(root, source, link);
            counter.file(stat.st_size);
            try { Os.symlink(link, target.getPath()); }
            catch (Exception error) { throw new IOException("private_asset_link_create_failed", error); }
            return;
        }
        if (isDirectory(stat)) {
            counter.directory();
            if (!target.mkdir()) throw new IOException("snapshot_directory_create_failed");
            for (File child : children(source)) {
                requireContainedEntry(root, child);
                copy(root, child, new File(target, child.getName()), depth + 1, counter);
            }
            return;
        }
        if (!isFile(stat)) throw new IOException("private_asset_unsupported_entry");
        counter.file(stat.st_size);
        byte[] buffer = new byte[64 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(source));
             FileOutputStream bytes = new FileOutputStream(target);
             BufferedOutputStream output = new BufferedOutputStream(bytes)) {
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            output.flush();
            bytes.getFD().sync();
        }
    }

    private static void digestTree(File root, File current, int depth, Counter counter,
                                   MessageDigest digest) throws IOException {
        StructStat stat = lstat(current);
        counter.enter(current, depth);
        String relative = root.equals(current) ? "." : root.toURI().relativize(current.toURI()).getPath();
        if (isLink(stat)) {
            String link = readLink(current);
            validateLink(root, current, link);
            byte[] bytes = link.getBytes("UTF-8");
            counter.file(stat.st_size);
            update(digest, "L", relative, bytes.length);
            digest.update(bytes);
            return;
        }
        if (isDirectory(stat)) {
            counter.directory();
            update(digest, "D", relative, 0);
            for (File child : children(current)) {
                requireContainedEntry(root, child);
                digestTree(root, child, depth + 1, counter, digest);
            }
            return;
        }
        if (!isFile(stat)) throw new IOException("private_asset_unsupported_entry");
        counter.file(stat.st_size);
        update(digest, "F", relative, stat.st_size);
        byte[] buffer = new byte[64 * 1024];
        try (BufferedInputStream input = new BufferedInputStream(new FileInputStream(current))) {
            int count;
            while ((count = input.read(buffer)) >= 0) if (count > 0) digest.update(buffer, 0, count);
        }
    }

    private static void delete(File root, File current, int depth) throws IOException {
        if (depth > MUTATION_LIMITS.maxDepth) throw new IOException("private_asset_depth_limit");
        StructStat stat = lstat(current);
        if (isDirectory(stat) && !isLink(stat)) {
            for (File child : children(current)) {
                requireContainedEntry(root, child);
                delete(root, child, depth + 1);
            }
        }
        if (!current.delete()) throw new IOException("private_asset_delete_failed");
    }

    private static File canonicalRoot(File source) throws IOException {
        if (isLink(lstat(source))) throw new IOException("private_asset_link_rejected");
        return source.getCanonicalFile();
    }

    private static void requireContainedEntry(File root, File child) throws IOException {
        File parent = child.getParentFile();
        if (parent == null) throw new IOException("private_asset_path_escape");
        File expected = new File(parent.getCanonicalFile(), child.getName()).getAbsoluteFile();
        if (!expected.equals(child.getAbsoluteFile()) || !isContained(root, expected)) {
            throw new IOException("private_asset_path_escape");
        }
    }

    private static void validateLink(File root, File source, String target) throws IOException {
        if (target.isEmpty() || target.indexOf('\0') >= 0 || target.indexOf('\\') >= 0) {
            throw new IOException("private_asset_link_unsafe");
        }
        if (target.startsWith("/")) {
            if (!isDosDevice(source) || !isAllowedDosDeviceTarget(target)) {
                throw new IOException("private_asset_link_unsafe");
            }
            return;
        }
        File resolved = new File(source.getParentFile(), target).getCanonicalFile();
        if (!isContained(root, resolved)) throw new IOException("private_asset_link_unsafe");
    }

    private static boolean isDosDevice(File source) {
        File parent = source.getParentFile();
        return parent != null && "dosdevices".equals(parent.getName());
    }

    private static boolean isAllowedDosDeviceTarget(String target) {
        return "/".equals(target) || target.startsWith("/data/data/com.termux/") ||
            target.startsWith("/data/user/0/com.termux/") || "/sdcard".equals(target) ||
            target.startsWith("/sdcard/") || "/storage".equals(target) ||
            target.startsWith("/storage/") || target.matches("/dev/ttyS[0-9]+");
    }

    private static boolean isContained(File root, File candidate) {
        return candidate.equals(root) || candidate.getPath().startsWith(root.getPath() + File.separator);
    }

    private static StructStat lstat(File file) throws IOException {
        try { return Os.lstat(file.getPath()); }
        catch (Exception error) { throw new IOException("private_asset_stat_failed", error); }
    }

    private static String readLink(File file) throws IOException {
        try { return Os.readlink(file.getPath()); }
        catch (Exception error) { throw new IOException("private_asset_link_read_failed", error); }
    }

    private static boolean isLink(StructStat stat) { return OsConstants.S_ISLNK(stat.st_mode); }
    private static boolean isDirectory(StructStat stat) { return OsConstants.S_ISDIR(stat.st_mode); }
    private static boolean isFile(StructStat stat) { return OsConstants.S_ISREG(stat.st_mode); }

    private static void update(MessageDigest digest, String type, String path, long size)
        throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(type);
            output.writeUTF(path);
            output.writeLong(size);
        }
        digest.update(bytes.toByteArray());
    }

    private static File[] children(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("private_asset_unreadable");
        Arrays.sort(children, Comparator.comparing(File::getName));
        return children;
    }

    static final class Limits {
        final long maxEntries;
        final long maxBytes;
        final int maxDepth;
        Limits(long maxEntries, long maxBytes, int maxDepth) {
            this.maxEntries = maxEntries;
            this.maxBytes = maxBytes;
            this.maxDepth = maxDepth;
        }
    }

    static final class Stats {
        final long bytes;
        final long files;
        final long directories;
        final boolean incomplete;
        Stats(long bytes, long files, long directories, boolean incomplete) {
            this.bytes = bytes;
            this.files = files;
            this.directories = directories;
            this.incomplete = incomplete;
        }
    }

    private static final class Counter {
        private final Limits limits;
        private final boolean failOnLimit;
        private long entries;
        private long bytes;
        private long files;
        private long directories;
        private boolean incomplete;
        Counter(Limits limits, boolean failOnLimit) {
            this.limits = limits;
            this.failOnLimit = failOnLimit;
        }
        void enter(File file, int depth) throws IOException {
            if (depth > limits.maxDepth || entries == limits.maxEntries) limit();
            if (canContinue()) entries++;
        }
        void file(long size) throws IOException {
            if (!canContinue()) return;
            if (size < 0 || bytes > limits.maxBytes - size) { limit(); return; }
            bytes += size;
            files++;
        }
        void directory() { if (canContinue()) directories++; }
        boolean canContinue() { return !incomplete; }
        private void limit() throws IOException {
            incomplete = true;
            if (failOnLimit) throw new IOException("private_asset_limit_exceeded");
        }
        Stats stats() { return new Stats(bytes, files, directories, incomplete); }
    }
}
