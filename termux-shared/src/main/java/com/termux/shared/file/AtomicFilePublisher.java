package com.termux.shared.file;

import android.system.ErrnoException;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Linux atomic no-replace rename. Unsupported filesystems fail without another publish path. */
public final class AtomicFilePublisher {
    private static boolean loaded;
    private AtomicFilePublisher() {}

    public static void moveNoReplace(File source, File destination) throws IOException {
        load();
        int error = renameNoReplace(path(source), path(destination));
        if (error != 0) throw new IOException("archive_publish_failed:errno:" + error,
            new ErrnoException("renameat2(RENAME_NOREPLACE)", error));
    }

    private static synchronized void load() throws IOException {
        if (loaded) return;
        try { System.loadLibrary("termux-file-publish"); loaded = true; }
        catch (LinkageError error) { throw new IOException("archive_publish_unavailable", error); }
    }

    private static byte[] path(File file) throws IOException {
        String path = file.getAbsolutePath();
        if (path.indexOf('\0') >= 0) throw new IOException("archive_invalid_path");
        return (path + "\0").getBytes(StandardCharsets.UTF_8);
    }

    private static native int renameNoReplace(byte[] source, byte[] destination);
}
