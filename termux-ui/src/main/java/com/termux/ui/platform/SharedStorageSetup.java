package com.termux.ui.platform;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** UI equivalent of termux-setup-storage, without its interactive directory wipe. */
final class SharedStorageSetup {
    private SharedStorageSetup() { }

    static void prepare(Context context) throws IOException {
        ExternalStorageAccess.requirePermission(context);
        File shared = Environment.getExternalStorageDirectory();
        if (shared.list() == null) throw new IOException(context.getString(
            com.termux.ui.R.string.external_storage_permission_required, shared.getAbsolutePath()));
        ExternalStoragePaths.requireWritable(context, new File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Termux"));
        StorageLinks.ensure(new File(context.getFilesDir(), "home/storage"), targets(context), new StorageLinks.FileSystem() {
            public StorageLinks.Kind kind(File file) throws IOException {
                try {
                    int mode = Os.lstat(file.getPath()).st_mode;
                    return OsConstants.S_ISLNK(mode) ? StorageLinks.Kind.LINK
                        : OsConstants.S_ISDIR(mode) ? StorageLinks.Kind.DIRECTORY : StorageLinks.Kind.OTHER;
                } catch (ErrnoException error) {
                    if (error.errno == OsConstants.ENOENT) return StorageLinks.Kind.MISSING;
                    throw new IOException(error);
                }
            }
            public String readLink(File file) throws IOException {
                try { return Os.readlink(file.getPath()); } catch (ErrnoException error) { throw new IOException(error); }
            }
            public void mkdirs(File file) throws IOException {
                if (!file.mkdirs() && !file.isDirectory()) throw new IOException("storage_directory_failed: " + file);
            }
            public void unlink(File file) throws IOException {
                try { Os.remove(file.getPath()); } catch (ErrnoException error) { throw new IOException(error); }
            }
            public void symlink(String target, File file) throws IOException {
                try { Os.symlink(target, file.getPath()); } catch (ErrnoException error) { throw new IOException(error); }
            }
        });
    }

    static boolean linksPresent(Context context) {
        File storage = new File(context.getFilesDir(), "home/storage");
        try {
            if (!OsConstants.S_ISDIR(Os.lstat(storage.getPath()).st_mode)) return false;
            for (Map.Entry<String, File> entry : targets(context).entrySet())
                if (!entry.getValue().getAbsolutePath().equals(Os.readlink(new File(storage, entry.getKey()).getPath())))
                    return false;
            return Environment.getExternalStorageDirectory().canRead();
        } catch (ErrnoException | SecurityException error) { return false; }
    }

    private static Map<String, File> targets(Context context) {
        Map<String, File> targets = new LinkedHashMap<>();
        targets.put("shared", Environment.getExternalStorageDirectory());
        targets.put("documents", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS));
        targets.put("downloads", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS));
        targets.put("dcim", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM));
        targets.put("pictures", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES));
        targets.put("music", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC));
        targets.put("movies", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES));
        targets.put("podcasts", Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS));
        if (Build.VERSION.SDK_INT >= 29) targets.put("audiobooks",
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_AUDIOBOOKS));
        File[] external = context.getExternalFilesDirs(null);
        if (external != null) for (int i = 0; i < external.length; i++)
            if (external[i] != null) targets.put("external-" + i, external[i]);
        File[] media = context.getExternalMediaDirs();
        if (media != null) for (int i = 0; i < media.length; i++)
            if (media[i] != null) targets.put("media-" + i, media[i]);
        return targets;
    }
}
