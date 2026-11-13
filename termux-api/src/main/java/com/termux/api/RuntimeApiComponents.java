package com.termux.api;

import org.json.JSONObject;
import org.json.JSONArray;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Verified companion payloads; independent of Android so recovery is exercised on the JVM. */
public class RuntimeApiComponents {
    private static final int MAX_ARCHIVE = 2 * 1024 * 1024;
    private static final int MAX_EXPANDED = 4 * 1024 * 1024;
    private static final String RECEIPT = "share/embedded-api/receipt.json";
    public interface Platform {
        boolean isSymlink(File path) throws IOException;
        void chmod(File path, int mode) throws IOException;
    }
    public interface Downloader { void fetch(String url, File target, long expectedSize) throws IOException; }
    private final Platform platform;
    private final Downloader downloader;
    public RuntimeApiComponents(Platform platform, Downloader downloader) {
        this.platform = platform;
        this.downloader = downloader;
    }

    public static JSONObject resolve(JSONObject catalog, String abi) throws IOException {
        try {
            if (catalog.getInt("schema") != 1 || !"embedded-v1".equals(catalog.getString("contract")))
                throw new IOException("API catalog contract mismatch");
            JSONObject component = catalog.getJSONObject("components").getJSONObject(abi);
            if (!abi.equals(component.getString("abi")) || !"embedded-v1".equals(component.getString("contract")))
                throw new IOException("API component ABI/contract mismatch");
            validate(component);
            return component;
        } catch (org.json.JSONException error) { throw new IOException("No matching API component for " + abi, error); }
    }

    private static void validate(JSONObject component) throws IOException {
        try {
            long size = component.getLong("size");
            if (size <= 0 || size > MAX_ARCHIVE || !component.getString("sha256").matches("[0-9a-f]{64}"))
                throw new IOException("Invalid API archive identity");
            JSONObject files = component.getJSONObject("files");
            if (files.length() < 3 || files.length() > 128) throw new IOException("Invalid API file inventory");
            long total = 0;
            for (String name : keys(files)) {
                if (!name.matches("bin/termux-[A-Za-z0-9_-]+|libexec/termux-(api|callback)|share/embedded-api/(termux-api|upower)\\.deb"))
                    throw new IOException("Invalid API target: " + name);
                JSONObject entry = files.getJSONObject(name);
                long length = entry.getLong("size");
                if (length < 0 || length > MAX_EXPANDED || !entry.getString("sha256").matches("[0-9a-f]{64}"))
                    throw new IOException("Invalid API file identity");
                total += length;
            }
            if (total > MAX_EXPANDED || !files.has("libexec/termux-api") || !files.has("libexec/termux-callback")
                    || !files.has("share/embedded-api/termux-api.deb")) throw new IOException("Incomplete API inventory");
        } catch (org.json.JSONException error) { throw new IOException("Invalid API component", error); }
    }

    /** Called only from an explicit initialization/repair task, never desktop launch. */
    public void install(File inputPrefix, JSONObject component) throws IOException {
        validate(component);
        File prefix = inputPrefix.getCanonicalFile();
        if (!new File(prefix, "bin").isDirectory()) throw new IOException("Termux prefix is not initialized");
        File state = safe(prefix, ".runtime-api"); mkdir(state);
        File lockPath = safe(state, "install.lock");
        try (RandomAccessFile lockFile = new RandomAccessFile(lockPath, "rw")) {
            FileLock lock;
            try { lock = lockFile.getChannel().tryLock(); }
            catch (OverlappingFileLockException error) { throw new IOException("API installation already running", error); }
            if (lock == null) throw new IOException("API installation already running");
            try {
                File pending = safe(state, "pending");
                File committed = safe(state, "committed");
                recover(prefix, pending, component);
                remove(committed);
                // An exact legacy installation can be adopted offline without trusting a stale receipt.
                if (matches(prefix, component)) {
                    writeReceipt(prefix, component);
                    return;
                }
                File cache = safe(state, "cache"); mkdir(cache);
                String hash = component.getString("sha256");
                File archive = safe(cache, hash + ".zip");
                if (!matchesFile(archive, component)) {
                    if (archive.exists() && !archive.delete()) throw new IOException("Cannot discard damaged API cache");
                    File partial = File.createTempFile(".download-", ".part", cache);
                    try {
                        downloader.fetch(component.getString("url"), partial, component.getLong("size"));
                        if (!matchesFile(partial, component)) throw new IOException("API download size/SHA-256 mismatch; retry initialization");
                        move(partial, archive);
                    } finally { partial.delete(); }
                }
                mkdir(pending);
                try {
                    File staged = safe(pending, "staged"); mkdir(staged);
                    extract(archive, staged, component.getJSONObject("files"));
                    // The durable plan precedes every modification of active files.
                    JSONArray plan = new JSONArray();
                    List<String> names = new ArrayList<>(); names.add(RECEIPT); names.addAll(keys(component.getJSONObject("files")));
                    for (String name : names) {
                        File target = safe(prefix, name);
                        if (target.exists() && !target.isFile()) throw new IOException("API target is not a regular file: " + name);
                        plan.put(new JSONObject().put("path", name).put("original", target.exists()));
                    }
                    File planTemporary = safe(pending, "plan.tmp");
                    write(planTemporary, plan.toString().getBytes(StandardCharsets.UTF_8));
                    move(planTemporary, safe(pending, "plan.json"));
                    File stagedReceipt = safe(staged, RECEIPT); mkdir(stagedReceipt.getParentFile());
                    write(stagedReceipt, receipt(component));
                    for (String name : names) {
                        interrupted();
                        File target = safe(prefix, name); mkdir(target.getParentFile());
                        if (target.exists()) {
                            File backup = safe(pending, "backup/" + name); mkdir(backup.getParentFile());
                            move(target, backup);
                        }
                        File source = safe(staged, name);
                        platform.chmod(source, name.startsWith("bin/") || name.startsWith("libexec/") ? 0700 : 0600);
                        move(source, target);
                    }
                    if (!matches(prefix, component)) throw new IOException("Installed API files failed verification");
                    // A single directory rename commits the transaction. A crash before it rolls back.
                    move(pending, committed);
                    remove(committed);
                } catch (IOException | RuntimeException error) {
                    try { recover(prefix, pending, component); }
                    catch (IOException recovery) { error.addSuppressed(recovery); }
                    throw error;
                }
            } finally { lock.release(); }
        } catch (org.json.JSONException error) { throw new IOException("Invalid API metadata", error); }
    }

    private void recover(File prefix, File pending, JSONObject component) throws IOException {
        if (!pending.exists()) return;
        File planFile = safe(pending, "plan.json");
        if (planFile.isFile()) {
            try {
                JSONArray plan = new JSONArray(new String(read(planFile, 64 * 1024), StandardCharsets.UTF_8));
                Set<String> seen = new HashSet<>();
                if (plan.length() != component.getJSONObject("files").length() + 1)
                    throw new IOException("Incomplete API recovery plan");
                // Validate the complete journal before changing any active file.
                for (int i = 0; i < plan.length(); i++) {
                    JSONObject entry = plan.getJSONObject(i); String name = entry.getString("path");
                    if ((!name.equals(RECEIPT) && !component.getJSONObject("files").has(name)) || !seen.add(name))
                        throw new IOException("Unsafe API recovery plan");
                    entry.getBoolean("original"); safe(prefix, name); safe(pending, "backup/" + name);
                }
                for (int i = 0; i < plan.length(); i++) {
                    JSONObject entry = plan.getJSONObject(i); String name = entry.getString("path");
                    File target = safe(prefix, name); File backup = safe(pending, "backup/" + name);
                    if (entry.getBoolean("original")) {
                        if (backup.exists()) {
                            if (target.exists() && !target.delete()) throw new IOException("Cannot roll back API file");
                            mkdir(target.getParentFile()); move(backup, target);
                        }
                    } else if (target.exists() && !target.delete()) throw new IOException("Cannot remove incomplete API file");
                }
            } catch (org.json.JSONException error) { throw new IOException("Invalid API recovery journal", error); }
        }
        remove(pending);
    }

    private void extract(File archive, File root, JSONObject files) throws IOException {
        Set<String> seen = new HashSet<>(); long total = 0;
        try (ZipFile zip = new ZipFile(archive, "UTF-8")) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                String name = entry.getName(); int type = entry.getUnixMode() & 0170000;
                if (entry.isDirectory() || entry.isUnixSymlink() || (type != 0 && type != 0100000)
                        || !zip.canReadEntryData(entry) || !files.has(name) || !seen.add(name))
                    throw new IOException("Unsafe API archive member: " + name);
                JSONObject expected = files.getJSONObject(name);
                File target = safe(root, name); mkdir(target.getParentFile());
                try (InputStream input = zip.getInputStream(entry); FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192]; int count; long length = 0;
                    while ((count = input.read(buffer)) != -1) {
                        interrupted(); length += count; total += count;
                        if (length > expected.getLong("size") || total > MAX_EXPANDED) throw new IOException("Oversized API archive");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                }
                if (!matchesFile(target, expected)) throw new IOException("API file checksum mismatch: " + name);
            }
        } catch (org.json.JSONException error) { throw new IOException("Invalid API file metadata", error); }
        if (seen.size() != files.length()) throw new IOException("Incomplete API archive");
    }

    public boolean matches(File root, JSONObject component) throws IOException {
        try {
            JSONObject files = component.getJSONObject("files");
            for (String name : keys(files)) {
                File path = safe(root, name);
                if (!matchesFile(path, files.getJSONObject(name))
                        || (files.getJSONObject(name).getBoolean("executable") && !path.canExecute())) return false;
            }
            return true;
        } catch (org.json.JSONException error) { throw new IOException("Invalid API inventory", error); }
    }
    private void writeReceipt(File prefix, JSONObject component) throws IOException, org.json.JSONException {
        File target = safe(prefix, RECEIPT); mkdir(target.getParentFile());
        File temp = File.createTempFile(".receipt-", ".tmp", target.getParentFile());
        try { write(temp, receipt(component)); move(temp, target); }
        finally { temp.delete(); }
    }
    private static byte[] receipt(JSONObject c) throws org.json.JSONException {
        return (c.getString("abi") + "\n" + c.getString("contract") + "\n" + c.getString("sha256") + "\n").getBytes(StandardCharsets.UTF_8);
    }
    private File safe(File root, String relative) throws IOException {
        if (relative.isEmpty() || relative.startsWith("/") || relative.contains("\\")) throw new IOException("Unsafe API path");
        File current = root.getCanonicalFile();
        if (platform.isSymlink(root)) throw new IOException("Unsafe API directory");
        for (String part : relative.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Unsafe API path");
            current = new File(current, part);
            if (platform.isSymlink(current) || !current.getCanonicalFile().equals(current.getAbsoluteFile()))
                throw new IOException("Symlink in API path: " + relative);
        }
        return current;
    }
    private void remove(File path) throws IOException {
        if (!path.exists() && !platform.isSymlink(path)) return;
        if (platform.isSymlink(path)) throw new IOException("Unsafe API temporary directory");
        if (path.isDirectory()) {
            File[] children = path.listFiles(); if (children == null) throw new IOException("Cannot list " + path);
            for (File child : children) remove(child);
        }
        if (!path.delete()) throw new IOException("Cannot delete " + path);
    }
    protected void move(File source, File target) throws IOException {
        if (!source.renameTo(target)) throw new IOException("Cannot activate API file: " + target);
    }
    private static void mkdir(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
    }
    private static List<String> keys(JSONObject object) {
        List<String> result = new ArrayList<>(); Iterator<String> i = object.keys();
        while (i.hasNext()) result.add(i.next()); Collections.sort(result); return result;
    }
    private static void write(File path, byte[] data) throws IOException {
        try (FileOutputStream output = new FileOutputStream(path)) { output.write(data); output.getFD().sync(); }
    }
    static byte[] read(File path, int limit) throws IOException {
        try (InputStream input = new FileInputStream(path); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > limit) throw new IOException("Oversized API metadata");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }
    static boolean matchesFile(File file, JSONObject expected) throws IOException, org.json.JSONException {
        if (!file.isFile() || file.length() != expected.getLong("size")) return false;
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
            StringBuilder actual = new StringBuilder();
            for (byte value : hash.digest()) actual.append(String.format(Locale.ROOT, "%02x", value & 255));
            return actual.toString().equals(expected.getString("sha256"));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }
    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("API initialization cancelled");
    }
    public static void downloadHttps(String address, File target, long expectedSize) throws IOException {
        URL url = new URL(address); long deadline = System.nanoTime() + 180_000_000_000L;
        for (int redirects = 0; redirects <= 5; redirects++) {
            interrupted();
            if (System.nanoTime() > deadline) throw new IOException("API download timed out");
            if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null)
                throw new IOException("API download requires HTTPS");
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(30_000); connection.setReadTimeout(30_000);
            connection.setRequestProperty("User-Agent", "Termux-App-components/1");
            try {
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("API download redirect missing Location");
                    url = new URL(url, location); continue;
                }
                if (status != 200) throw new IOException("API component download HTTP " + status + "; retry initialization");
                try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(target)) {
                    byte[] buffer = new byte[8192]; int count; long total = 0;
                    while ((count = input.read(buffer)) != -1) {
                        interrupted(); total += count;
                        if (System.nanoTime() > deadline || total > expectedSize) throw new IOException("API download exceeds time/size limit");
                        output.write(buffer, 0, count);
                    }
                    output.getFD().sync();
                    if (total != expectedSize) throw new IOException("Incomplete API component download");
                    return;
                }
            } finally { connection.disconnect(); }
        }
        throw new IOException("Too many API download redirects");
    }
}
