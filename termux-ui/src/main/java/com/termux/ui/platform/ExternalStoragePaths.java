package com.termux.ui.platform;

import android.content.Context;
import android.os.Environment;
import com.termux.ui.R;
import java.io.File;
import java.io.IOException;

/** One shallow public location; failures never redirect writes into another directory. */
public final class ExternalStoragePaths {
    private ExternalStoragePaths() { }
    public static File components() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Termux/components");
    }
    public static File backups() {
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Termux/backups");
    }
    public static File requireWritable(Context context, File directory) throws IOException {
        try {
            if ((directory.isDirectory() || directory.mkdirs()) && directory.canWrite()) {
                File probe = File.createTempFile(".write-", ".tmp", directory);
                if (!probe.delete()) throw new IOException("storage_probe_cleanup_failed");
                return directory;
            }
        } catch (SecurityException | IOException ignored) { }
        throw new IOException(context.getString(R.string.external_storage_permission_required, directory.getAbsolutePath()));
    }
}
