package com.termux.ui.wine.recovery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import org.apache.commons.compress.archivers.zip.*;

/** Only drive_c and Wine's three registry files cross the archive boundary. */
public final class WineCDriveArchive {
    public static final String EXTENSION = ".wine-cdrive.zip";
    private static final String MANIFEST = "cdrive.properties";
    private static final List<String> REGISTRY = Arrays.asList("system.reg", "user.reg", "userdef.reg");
    private static final long MAX_BYTES = 128L << 30;
    private static final int MAX_ENTRIES = 1000000;
    public interface Progress { void update(String message); }
    public abstract static class OwnedOutput extends OutputStream { public abstract String identity(); }
    public interface FileSystem {
        /** lstat: 0 missing, 1 regular, 2 directory, 3 symbolic link; other kinds fail. */
        int kind(File file) throws IOException;
        int mode(File file) throws IOException;
        InputStream read(File file) throws IOException;
        OwnedOutput create(File file) throws IOException;
        String identity(File file) throws IOException;
        String readLink(File file) throws IOException;
        void link(String target, File file) throws IOException;
        void chmod(File file, int mode) throws IOException;
        void move(File source, File target) throws IOException;
    }
    private final FileSystem fs;
    public WineCDriveArchive(FileSystem fs) { this.fs = fs; }

    public static File stage(File prefix, String token) { return new File(prefix.getParentFile(), ".cdrive-" + token + "-stage"); }
    public static File old(File prefix, String token) { return new File(prefix.getParentFile(), ".cdrive-" + token + "-old"); }

    public void backup(File prefix, File target, String wine, String token, Progress progress) throws IOException {
        requirePrefix(prefix);
        if (fs.kind(target) != 0) throw fail("cdrive_destination_exists");
        boolean completed = false;
        String identity = "";
        try {
            OwnedOutput file = fs.create(target);
            identity = file.identity();
            try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(new BufferedOutputStream(file, 65536))) {
                progress.update("cdrive_output:" + identity);
                zip.setUseZip64(Zip64Mode.AsNeeded);
                zip.setEncoding("UTF-8");
                zip.setLevel(1);
                Properties meta = new Properties();
                meta.setProperty("format", "wine-cdrive-1");
                meta.setProperty("backend", "rootfs_proot");
                meta.setProperty("winePackage", wine);
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                meta.store(bytes, null);
                ZipArchiveEntry manifest = new ZipArchiveEntry(MANIFEST);
                manifest.setUnixMode(0100600);
                zip.putArchiveEntry(manifest);
                zip.write(bytes.toByteArray());
                zip.closeArchiveEntry();
                long[] counts = {0, 0};
                write(prefix, new File(prefix, "drive_c"), "drive_c", zip, counts, progress, 0);
                for (String registry : REGISTRY) write(prefix, new File(prefix, registry), registry, zip, counts, progress, 0);
                zip.finish();
            }
            progress.update("cdrive_committed");
            completed = true;
        } finally { if (!completed && !identity.isEmpty()) deleteOwned(target, identity); }
    }

    public void restore(File prefix, File archive, String wine, String token, Progress progress) throws IOException {
        if (fs.kind(prefix) != 2) throw fail("cdrive_not_prepared");
        File stage = stage(prefix, token), old = old(prefix, token);
        if (fs.kind(stage) != 0 || fs.kind(old) != 0) throw fail("cdrive_transaction_exists");
        mkdir(stage);
        boolean committed = false;
        try {
            try (ZipFile zip = new ZipFile(archive, "UTF-8")) {
                List<ZipArchiveEntry> entries = Collections.list(zip.getEntries());
                if (entries.isEmpty() || entries.size() > MAX_ENTRIES || !MANIFEST.equals(entries.get(0).getName()))
                    throw fail("cdrive_archive_format");
                Properties meta = new Properties();
                meta.load(new ByteArrayInputStream(read(zip, entries.get(0), 16384)));
                if (meta.size() != 3 || !"wine-cdrive-1".equals(meta.getProperty("format"))
                    || !"rootfs_proot".equals(meta.getProperty("backend"))) throw fail("cdrive_archive_format");
                if (!wine.equals(meta.getProperty("winePackage"))) throw fail("cdrive_wine_mismatch");
                Set<String> names = new HashSet<>();
                List<ZipArchiveEntry> links = new ArrayList<>();
                long total = 0;
                for (int index = 1; index < entries.size(); index++) {
                    ZipArchiveEntry entry = entries.get(index);
                    String name = name(entry);
                    if (!names.add(name)) throw fail("cdrive_duplicate_entry");
                    if (entry.getSize() < 0 || entry.getSize() > MAX_BYTES - total) throw fail("cdrive_archive_limit");
                    total += entry.getSize();
                    if (!zip.canReadEntryData(entry)) throw fail("cdrive_archive_unsupported");
                    if (entry.isUnixSymlink()) links.add(entry);
                    else if (!entry.isDirectory() && (entry.getUnixMode() & 0170000) != 0100000)
                        throw fail("cdrive_archive_unsupported");
                }
                if (!names.contains("drive_c") || !names.containsAll(REGISTRY)) throw fail("cdrive_archive_incomplete");
                if (total > stage.getUsableSpace() - (16L << 20)) throw fail("cdrive_space_insufficient");
                for (ZipArchiveEntry entry : entries.subList(1, entries.size())) {
                    if (entry.isUnixSymlink()) continue;
                    String name = name(entry);
                    File target = new File(stage, name);
                    mkdir(target.getParentFile());
                    if (entry.isDirectory()) {
                        if (entry.getSize() != 0) throw fail("cdrive_directory_payload");
                        mkdir(target);
                    } else {
                        progress.update("Restoring " + name);
                        try (OutputStream output = fs.create(target)) { copy(zip, entry, output); }
                        fs.chmod(target, entry.getUnixMode() & 0777);
                        if (entry.getTime() >= 0 && !target.setLastModified(entry.getTime())) throw fail("cdrive_timestamp_failed");
                    }
                }
                // Links are materialized last. No archive entry can traverse a link during extraction.
                for (ZipArchiveEntry entry : links) {
                    String name = name(entry);
                    for (String candidate : names) if (candidate.startsWith(name + "/")) throw fail("cdrive_link_parent");
                    String target = new String(read(zip, entry, 4096), StandardCharsets.UTF_8);
                    if (target.isEmpty() || target.indexOf('\0') >= 0) throw fail("cdrive_invalid_link");
                    File link = new File(stage, name);
                    mkdir(link.getParentFile());
                    fs.link(target, link);
                }
            }
            requirePrefix(stage);
            // Mount links and readiness metadata belong to the current game, not the backup.
            for (File entry : children(prefix)) {
                if (!"drive_c".equals(entry.getName()) && !REGISTRY.contains(entry.getName()))
                    copyTree(entry, new File(stage, entry.getName()), 0);
            }
            // Explicit restore validates the archive's Wine identity. Re-establish a lost
            // identity marker without rebuilding C: warmup will keep this prefix and bind it
            // to the current rootfs. An ordinary save must never infer this from damaged data.
            File markers = new File(stage, ".games-runtime");
            mkdir(markers);
            File marker = new File(markers, "runtime");
            int markerKind = fs.kind(marker);
            if (markerKind != 0 && markerKind != 1) throw fail("cdrive_runtime_marker_invalid");
            boolean hasMarker = false;
            if (markerKind == 1) try (InputStream input = fs.read(marker)) { hasMarker = input.read() != -1; }
            if (!hasMarker) {
                File temporary = new File(markers, ".restore-" + token);
                try (OutputStream output = fs.create(temporary)) {
                    output.write(("restored|" + wine + "\n").getBytes(StandardCharsets.UTF_8));
                }
                if (markerKind == 1) delete(marker); // Empty marker in our unpublished stage only.
                fs.move(temporary, marker);
            }
            progress.update("Publishing C drive and registry");
            fs.move(prefix, old);
            fs.move(stage, prefix);
            committed = true;
            progress.update("cdrive_committed");
        } finally {
            if (!committed && fs.kind(old) != 0) {
                if (fs.kind(prefix) != 0) delete(prefix);
                fs.move(old, prefix);
            }
            if (fs.kind(stage) != 0) delete(stage);
        }
        delete(old);
    }

    /** An interrupted operation is never replayed; restore the original prefix if publication began. */
    public void recover(File prefix, File archive, String token, boolean restore, boolean committed, String outputIdentity) throws IOException {
        if (restore) {
            File old = old(prefix, token);
            if (fs.kind(old) != 0 && !committed) {
                if (fs.kind(prefix) != 0) delete(prefix);
                fs.move(old, prefix);
            }
            if (committed) delete(old);
            delete(stage(prefix, token));
        } else if (!committed && !outputIdentity.isEmpty()) deleteOwned(archive, outputIdentity);
    }
    private void deleteOwned(File file, String identity) throws IOException {
        if (fs.kind(file) == 0) return;
        if (!identity.equals(fs.identity(file))) throw fail("cdrive_output_changed");
        delete(file);
    }

    private void write(File prefix, File file, String path, ZipArchiveOutputStream zip, long[] count,
                       Progress progress, int depth) throws IOException {
        checkInterrupt();
        if (depth > 128 || ++count[0] > MAX_ENTRIES) throw fail("cdrive_archive_limit");
        int kind = fs.kind(file);
        if (kind == 0) throw fail("cdrive_source_missing");
        ZipArchiveEntry entry = new ZipArchiveEntry(path + (kind == 2 ? "/" : ""));
        entry.setUnixMode((kind == 1 ? 0100000 : kind == 2 ? 0040000 : 0120000) | (fs.mode(file) & 0777));
        if (kind != 3) entry.setTime(file.lastModified());
        name(entry);
        zip.putArchiveEntry(entry);
        if (kind == 1) {
            progress.update("Backing up " + path);
            try (InputStream input = fs.read(file)) {
                byte[] buffer = new byte[65536]; int length;
                while ((length = input.read(buffer)) != -1) {
                    checkInterrupt();
                    if (count[1] > MAX_BYTES - length) throw fail("cdrive_archive_limit");
                    count[1] += length;
                    zip.write(buffer, 0, length);
                }
            }
        } else if (kind == 3) {
            byte[] target = fs.readLink(file).getBytes(StandardCharsets.UTF_8);
            if (target.length > 4096) throw fail("cdrive_invalid_link");
            zip.write(target);
        } else if (kind != 2) throw fail("cdrive_file_type");
        zip.closeArchiveEntry();
        if (kind == 2) for (File child : children(file))
            write(prefix, child, path + "/" + child.getName(), zip, count, progress, depth + 1);
    }

    private String name(ZipArchiveEntry entry) throws IOException {
        String name = entry.getName();
        if (entry.isDirectory() && name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.length() > 4096 || name.indexOf('\\') >= 0 || name.indexOf('\0') >= 0) throw fail("cdrive_invalid_path");
        for (String part : name.split("/", -1))
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw fail("cdrive_invalid_path");
        if (!name.equals("drive_c") && !name.startsWith("drive_c/") && !REGISTRY.contains(name)) throw fail("cdrive_scope_violation");
        if (REGISTRY.contains(name) && (entry.isDirectory() || entry.isUnixSymlink())) throw fail("cdrive_registry_invalid");
        if (name.equals("drive_c") && !entry.isDirectory()) throw fail("cdrive_archive_incomplete");
        return name;
    }
    private byte[] read(ZipFile zip, ZipArchiveEntry entry, long max) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > max) throw fail("cdrive_archive_limit");
        ByteArrayOutputStream output = new ByteArrayOutputStream(); copy(zip, entry, output); return output.toByteArray();
    }
    private void copy(ZipFile zip, ZipArchiveEntry entry, OutputStream output) throws IOException {
        CRC32 crc = new CRC32(); long size = 0;
        try (InputStream input = zip.getInputStream(entry)) {
            byte[] buffer = new byte[65536]; int length;
            while ((length = input.read(buffer)) != -1) {
                checkInterrupt(); size += length;
                if (size > entry.getSize()) throw fail("cdrive_archive_size");
                output.write(buffer, 0, length); crc.update(buffer, 0, length);
            }
        }
        if (size != entry.getSize() || crc.getValue() != entry.getCrc()) throw fail("cdrive_archive_corrupt");
    }
    private void requirePrefix(File prefix) throws IOException {
        if (fs.kind(prefix) != 2 || fs.kind(new File(prefix, "drive_c")) != 2) throw fail("cdrive_not_prepared");
        for (String registry : REGISTRY) if (fs.kind(new File(prefix, registry)) != 1) throw fail("cdrive_registry_missing");
    }
    private void mkdir(File directory) throws IOException {
        int kind = fs.kind(directory);
        if (kind == 2) return;
        if (kind != 0) throw fail("cdrive_directory_failed");
        mkdir(directory.getParentFile());
        if (!directory.mkdir()) throw fail("cdrive_directory_failed");
    }
    private void copyTree(File source, File target, int depth) throws IOException {
        checkInterrupt(); if (depth > 128) throw fail("cdrive_archive_limit");
        int kind = fs.kind(source);
        if (kind == 2) { mkdir(target); for (File child : children(source)) copyTree(child, new File(target, child.getName()), depth + 1); }
        else if (kind == 3) fs.link(fs.readLink(source), target);
        else if (kind == 1) {
            try (InputStream input = fs.read(source); OutputStream output = fs.create(target)) {
                byte[] bytes = new byte[65536]; int n; while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
            }
        } else throw fail("cdrive_file_type");
        if (kind != 3) {
            fs.chmod(target, fs.mode(source) & 0777);
            if (!target.setLastModified(source.lastModified())) throw fail("cdrive_timestamp_failed");
        }
    }
    private void delete(File file) throws IOException {
        int kind = fs.kind(file); if (kind == 0) return;
        if (kind == 2) for (File child : children(file)) delete(child);
        if (!file.delete()) throw fail("cdrive_cleanup_failed");
    }
    private static File[] children(File directory) throws IOException {
        File[] files = directory.listFiles(); if (files == null) throw fail("cdrive_directory_unreadable");
        Arrays.sort(files, Comparator.comparing(File::getName)); return files;
    }
    private static void checkInterrupt() throws IOException { if (Thread.currentThread().isInterrupted()) throw fail("cdrive_interrupted"); }
    private static IOException fail(String code) { return new IOException(code); }
}
