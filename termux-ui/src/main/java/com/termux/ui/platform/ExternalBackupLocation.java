package com.termux.ui.platform;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** All exported backups and restore choices stay directly in Download/Termux/backups. */
public final class ExternalBackupLocation {
    private ExternalBackupLocation() { }
    public static File directory() { return ExternalStoragePaths.backups(); }
    public static File newArchive(Context context, String name) throws IOException {
        File root = ExternalStoragePaths.requireWritable(context, directory());
        if (!new File(name).getName().equals(name) || name.equals(".") || name.equals(".."))
            throw new IOException("backup_name_invalid");
        String selected = name;
        int extension = name.indexOf('.');
        String base = extension < 0 ? name : name.substring(0, extension);
        String suffix = extension < 0 ? "" : name.substring(extension);
        for (int i = 1; new File(root, selected).exists(); i++) selected = base + "-" + i + suffix;
        return new File(root, selected);
    }
    public static void requireArchive(File archive) throws IOException {
        if (!directory().getCanonicalFile().equals(archive.getCanonicalFile().getParentFile()))
            throw new IOException("backup_path_invalid");
    }
    public static List<File> archives(Context context, String extension) throws IOException {
        File root = ExternalStoragePaths.requireWritable(context, directory());
        File[] files = root.listFiles();
        if (files == null) throw new IOException(context.getString(com.termux.ui.R.string.external_storage_permission_required, root.getAbsolutePath()));
        List<File> result = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(extension)) continue;
            if (root.getCanonicalFile().equals(file.getCanonicalFile().getParentFile())) result.add(file);
        }
        result.sort(Comparator.comparing(File::getName).reversed());
        return result;
    }
}
