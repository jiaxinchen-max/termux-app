package com.termux.ui.wine.recovery;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Read-only logical byte count. Never follows symlinks; counts each inode once per scan. */
public final class ReadOnlyTreeMeasure {
    private static final int MAX_ENTRIES = 500_000;
    private static final int MAX_DEPTH = 128;
    private final Set<String> seen = new HashSet<>();
    private int visited;

    public static final class Stats {
        public long bytes;
        public long files;
        public boolean exists;
        public boolean incomplete;
    }

    public void add(File file, Stats result) {
        visit(file, result, 0);
    }

    private void visit(File file, Stats result, int depth) {
        if (Thread.currentThread().isInterrupted() || depth > MAX_DEPTH || visited >= MAX_ENTRIES) {
            result.incomplete = true;
            return;
        }
        StructStat stat;
        try {
            stat = Os.lstat(file.getPath());
        } catch (ErrnoException error) {
            if (error.errno != OsConstants.ENOENT) result.incomplete = true;
            return;
        }
        result.exists = true;
        if (!seen.add(stat.st_dev + ":" + stat.st_ino)) return;
        ++visited;
        if (OsConstants.S_ISDIR(stat.st_mode)) {
            File[] children = file.listFiles();
            if (children == null) { result.incomplete = true; return; }
            for (File child : children) visit(child, result, depth + 1);
        } else if (OsConstants.S_ISREG(stat.st_mode) || OsConstants.S_ISLNK(stat.st_mode)) {
            result.bytes += Math.max(0, stat.st_size);
            ++result.files;
        }
    }
}
