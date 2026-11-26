package com.termux.ui.ide;

import android.content.Context;
import android.util.AtomicFile;
import com.termux.ui.components.*;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;
import java.io.*;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Properties;
import java.util.jar.JarFile;

/** Uses shared component downloading with IDE-owned tasks; no Wine installation gate or runtime. */
final class IdeToolingRuntime {
    private static final int PROTOCOL = 1;
    static final class Pin {
        final String sha256, url;
        final long size;
        final int version;
        Pin(InputStream input) throws IOException {
            Properties p = new Properties(); p.load(input);
            try {
                sha256 = p.getProperty("sha256", ""); url = p.getProperty("url", "");
                size = Long.parseLong(p.getProperty("size", "0"));
                version = Integer.parseInt(p.getProperty("version", "0"));
                if (!sha256.matches("[0-9a-f]{64}") || size < 1 || size > 64L * 1024 * 1024
                    || version < 1 || !"1".equals(p.getProperty("schemaVersion"))
                    || Integer.parseInt(p.getProperty("protocol", "0")) != PROTOCOL
                    || !url.startsWith("https://github.com/jiaxinchen-max/termux-app-packages/releases/download/"))
                    throw new IOException("Invalid IDE component pin");
            } catch (NumberFormatException e) { throw new IOException("Invalid IDE component pin", e); }
        }
    }
    interface Progress { void update(long downloaded, long total) throws IOException; }
    static synchronized File prepare(Context context, DownloadControl control, Progress progress) throws Exception {
        Pin pin;
        try (InputStream input = context.getAssets().open("ide/tooling/component.properties")) { pin = new Pin(input); }
        return prepare(new File(context.getFilesDir(), "ide-components"), pin, control, progress,
            url -> (HttpURLConnection) url.openConnection());
    }
    static File prepare(File root, Pin pin, DownloadControl control, Progress progress,
                        HttpConnectionFactory connections) throws Exception {
        check(control);
        File directory = new File(root, "install");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create IDE component storage");
        AtomicFile target = new AtomicFile(new File(directory, "tooling-" + pin.sha256 + ".jar"));
        // Recover AtomicFile backup before verification. Never execute the external download cache.
        try (InputStream ignored = target.openRead()) {
            if (valid(target.getBaseFile(), pin)) { progress.update(pin.size, pin.size); return target.getBaseFile(); }
        } catch (FileNotFoundException ignored) { }
        FileComponentTaskRepository repository = new FileComponentTaskRepository(new File(root, "tasks"));
        // A stable identity enables HTTP range/cached payload reuse after cancellation or process death.
        ComponentTask task = ComponentTask.queued("tooling-" + pin.sha256, "ide-tooling", pin.version,
            pin.url, pin.size, pin.sha256);
        repository.save(task);
        ComponentDownloader downloader = new ComponentDownloader(repository, new File(root, "downloads"), connections);
        ComponentDownloadResult result = downloader.download(task, control, updated -> {
            try { progress.update(updated.getDownloadedBytes(), pin.size); }
            catch (IOException e) { throw new java.io.UncheckedIOException(e); }
        });
        check(control);
        if (result.getTask().getState() != ComponentTaskState.VERIFIED || result.getArtifact() == null)
            throw new IOException("IDE component download did not finish. Run sync again to retry.");
        if (!valid(result.getArtifact(), pin)) throw new IOException("IDE component checksum or protocol mismatch");
        FileOutputStream output = null;
        try (InputStream input = new FileInputStream(result.getArtifact())) {
            output = target.startWrite();
            MessageDigest copied = MessageDigest.getInstance("SHA-256");
            byte[] bytes = new byte[32768]; int n; long count = 0;
            while ((n = input.read(bytes)) != -1) {
                check(control); count += n;
                if (count > pin.size) throw new IOException("IDE component size changed");
                copied.update(bytes, 0, n); output.write(bytes, 0, n);
            }
            StringBuilder sha = new StringBuilder();
            for (byte b : copied.digest()) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (count != pin.size || !pin.sha256.equals(sha.toString()))
                throw new IOException("IDE installed component verification failed");
            check(control); target.finishWrite(output); output = null;
        } catch (Exception e) { target.failWrite(output); throw e; }
        return target.getBaseFile();
    }
    static boolean valid(File file, Pin pin) throws Exception {
        if (!file.isFile() || file.length() != pin.size) return false;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[32768]; int n;
            while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n);
        }
        StringBuilder sha = new StringBuilder();
        for (byte b : digest.digest()) sha.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!pin.sha256.equals(sha.toString())) return false;
        try (JarFile jar = new JarFile(file)) {
            return jar.getManifest() != null && Integer.toString(PROTOCOL).equals(
                jar.getManifest().getMainAttributes().getValue("Termux-Tooling-Protocol"));
        }
    }
    private static void check(DownloadControl control) throws InterruptedException {
        if (Thread.currentThread().isInterrupted() || control.currentDecision() != DownloadControl.Decision.CONTINUE)
            throw new InterruptedException("IDE component preparation cancelled");
    }
    private IdeToolingRuntime() { }
}
