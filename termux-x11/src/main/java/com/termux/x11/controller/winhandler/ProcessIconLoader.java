package com.termux.x11.controller.winhandler;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Looks up the real icon of a Wine process (the icon of its .exe) without ever blocking the UI. A
 * process is resolved once on a background thread: its Linux pid must still carry the expected
 * name, the Windows path comes from {@code /proc/<pid>/cmdline}, the Wine prefix from
 * {@code /proc/<pid>/environ}, and the icon is read from the executable by {@link PeIconExtractor}.
 * Results (including "no icon") are cached per pid and name, so a reused pid cannot show a stale
 * icon. Every failure just means "no icon"; the caller then shows its default icon.
 */
public final class ProcessIconLoader {
    private static final int TARGET_PX = 64;
    private static final int MAX_ICON_PX = 96;
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int MAX_CMDLINE_BYTES = 8 * 1024;
    private static final int MAX_ENVIRON_BYTES = 128 * 1024;

    private static final class Cached {
        @Nullable Bitmap bitmap;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Cached> cache = new LinkedHashMap<String, Cached>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };
    private ExecutorService worker;
    private boolean released;

    /**
     * The icon of the process if it is already known, else null; the first call for a process
     * schedules the lookup, so the icon shows up on a later bind. Main thread.
     */
    @Nullable
    public Bitmap get(int pid, @NonNull String name) {
        if (released) return null;
        String key = pid + ":" + name;
        synchronized (cache) {
            Cached known = cache.get(key);
            if (known != null) return known.bitmap;
            cache.put(key, new Cached());
        }
        if (worker == null) {
            // The idle worker thread exits on its own, so a panel that is never released (the task
            // manager dialog) leaves nothing behind.
            ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 5L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "ProcessIconLoader");
                    thread.setDaemon(true);
                    return thread;
                });
            executor.allowCoreThreadTimeOut(true);
            worker = executor;
        }
        worker.execute(() -> {
            Bitmap bitmap = load(pid, name);
            if (bitmap == null) return;
            mainHandler.post(() -> {
                synchronized (cache) {
                    Cached entry = cache.get(key);
                    if (entry != null) entry.bitmap = bitmap;
                }
            });
        });
        return null;
    }

    /** Stops the worker and drops the cache; the loader returns nothing afterwards. Main thread. */
    public void release() {
        released = true;
        if (worker != null) worker.shutdownNow();
        worker = null;
        mainHandler.removeCallbacksAndMessages(null);
        synchronized (cache) {
            cache.clear();
        }
    }

    @Nullable
    private static Bitmap load(int pid, String name) {
        try {
            File proc = new File("/proc/" + pid);
            String status = readText(new File(proc, "status"), 4096);
            if (!hasName(status, name)) return null;
            String cmdline = readText(new File(proc, "cmdline"), MAX_CMDLINE_BYTES);
            List<String> candidates = WineProcessPaths.candidateWindowsPaths(cmdline, name);
            if (candidates.isEmpty()) return null;
            String prefixPath = WineProcessPaths.environmentValue(readText(new File(proc, "environ"), MAX_ENVIRON_BYTES), "WINEPREFIX");
            if (prefixPath == null || prefixPath.isEmpty()) return null;
            File prefix = new File(prefixPath);
            for (String windowsPath : candidates) {
                File exe = WineProcessPaths.toUnixFile(prefix, windowsPath);
                if (exe == null) continue;
                byte[] ico = PeIconExtractor.extractIcon(exe, TARGET_PX);
                if (ico == null) continue;
                Bitmap bitmap = BitmapFactory.decodeByteArray(ico, 0, ico.length);
                if (bitmap != null) return limit(bitmap);
            }
        } catch (RuntimeException | IOException | OutOfMemoryError e) {
            // No icon: the row keeps the default one.
        }
        return null;
    }

    private static boolean hasName(@Nullable String status, String name) {
        if (status == null) return false;
        for (String line : status.split("\n")) {
            if (line.startsWith("Name:")) return line.substring("Name:".length()).trim().equals(name);
        }
        return false;
    }

    private static Bitmap limit(Bitmap bitmap) {
        int largest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (largest <= MAX_ICON_PX) return bitmap;
        float scale = MAX_ICON_PX / (float) largest;
        Bitmap scaled = Bitmap.createScaledBitmap(bitmap,
            Math.max(1, Math.round(bitmap.getWidth() * scale)), Math.max(1, Math.round(bitmap.getHeight() * scale)), true);
        if (scaled != bitmap) bitmap.recycle();
        return scaled;
    }

    /** At most {@code maxBytes} of the file as text (cmdline/environ keep their NUL separators). */
    @Nullable
    private static String readText(File file, int maxBytes) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[maxBytes];
            int total = 0;
            int read;
            while (total < maxBytes && (read = in.read(buffer, total, maxBytes - total)) > 0) total += read;
            return new String(buffer, 0, total, StandardCharsets.UTF_8);
        }
    }
}
