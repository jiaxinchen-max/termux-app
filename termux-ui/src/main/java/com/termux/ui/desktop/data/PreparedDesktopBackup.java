package com.termux.ui.desktop.data;

import java.io.*;
import java.util.List;
import java.util.Properties;

/** Small managed startup files only; never copies a rootfs, device data or runtime credentials. */
public final class PreparedDesktopBackup {
    private final File directory, anchor;
    public PreparedDesktopBackup(File directory, File anchor) {
        this.directory = directory; this.anchor = anchor;
    }
    private File safe(File file) throws IOException {
        String base = anchor.getAbsolutePath() + File.separator;
        if (!file.getAbsolutePath().startsWith(base)) throw new IOException("desktop_backup_path_invalid");
        File current = anchor.getCanonicalFile();
        for (String part : file.getAbsolutePath().substring(base.length()).split("/")) {
            if (part.equals("..") || part.equals(".")) throw new IOException("desktop_backup_path_invalid");
            current = new File(current, part);
            if (!current.getCanonicalFile().equals(current.getAbsoluteFile()))
                throw new IOException("desktop_backup_symlink");
        }
        return current;
    }
    public void capture(List<File> files) throws IOException {
        if (files.size() > 16 || directory.exists() || !directory.mkdirs()) throw new IOException("desktop_backup_busy");
        Properties manifest = new Properties();
        manifest.setProperty("count", Integer.toString(files.size()));
        for (int i = 0; i < files.size(); i++) {
            File source = safe(files.get(i));
            manifest.setProperty(i + ".path", files.get(i).getAbsolutePath());
            manifest.setProperty(i + ".exists", Boolean.toString(source.exists()));
            if (source.exists()) {
                if (!source.isFile()) throw new IOException("desktop_backup_not_file");
                copy(source, new File(directory, Integer.toString(i)));
                manifest.setProperty(i + ".executable", Boolean.toString(source.canExecute()));
            }
        }
        try (FileOutputStream out = new FileOutputStream(new File(directory, "manifest"))) {
            manifest.store(out, null); out.getFD().sync();
        }
    }
    public void restore() throws IOException {
        File record = new File(directory, "manifest");
        if (!record.isFile()) return;
        Properties manifest = new Properties();
        try (FileInputStream in = new FileInputStream(record)) { manifest.load(in); }
        int count = Integer.parseInt(manifest.getProperty("count"));
        if (count < 0 || count > 16) throw new IOException("desktop_backup_invalid");
        for (int i = 0; i < count; i++) {
            File target = safe(new File(manifest.getProperty(i + ".path")));
            if (Boolean.parseBoolean(manifest.getProperty(i + ".exists"))) {
                File parent = target.getParentFile();
                if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("desktop_backup_restore_failed");
                File temporary = new File(parent, target.getName() + ".rollback");
                safe(temporary);
                copy(new File(directory, Integer.toString(i)), temporary);
                temporary.setExecutable(Boolean.parseBoolean(manifest.getProperty(i + ".executable")), true);
                if (!temporary.renameTo(target)) throw new IOException("desktop_backup_restore_failed");
            } else if (target.exists() && !target.delete()) throw new IOException("desktop_backup_restore_failed");
        }
    }
    public void discard() throws IOException {
        if (!directory.exists()) return;
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("desktop_backup_cleanup_failed");
        for (File child : children) if (!child.delete()) throw new IOException("desktop_backup_cleanup_failed");
        if (!directory.delete()) throw new IOException("desktop_backup_cleanup_failed");
    }
    private static void copy(File from, File to) throws IOException {
        if (from.length() > 1024 * 1024) throw new IOException("desktop_backup_too_large");
        try (InputStream in = new FileInputStream(from); FileOutputStream out = new FileOutputStream(to)) {
            byte[] buffer = new byte[8192]; int n, total = 0;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > 1024 * 1024) throw new IOException("desktop_backup_too_large");
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        }
    }
}
