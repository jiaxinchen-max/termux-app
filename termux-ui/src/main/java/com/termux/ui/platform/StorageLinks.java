package com.termux.ui.platform;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/** Repairs only the standard storage links. Never traverses or clears user directories. */
public final class StorageLinks {
    public enum Kind { MISSING, DIRECTORY, LINK, OTHER }
    public interface FileSystem {
        Kind kind(File file) throws IOException;
        String readLink(File file) throws IOException;
        void mkdirs(File file) throws IOException;
        void unlink(File file) throws IOException;
        void symlink(String target, File file) throws IOException;
    }
    private StorageLinks() { }

    public static void ensure(File directory, Map<String, File> targets, FileSystem fs) throws IOException {
        Kind root = fs.kind(directory);
        if (root == Kind.MISSING) fs.mkdirs(directory);
        else if (root != Kind.DIRECTORY) throw new IOException("storage_link_conflict: " + directory);
        // Check every collision before making changes; ordinary files are never replaced.
        for (String name : targets.keySet()) {
            if (!name.matches("[A-Za-z0-9_-]+")) throw new IOException("storage_link_name_invalid");
            File link = new File(directory, name);
            Kind kind = fs.kind(link);
            if (kind != Kind.MISSING && kind != Kind.LINK)
                throw new IOException("storage_link_conflict: " + link);
        }
        for (Map.Entry<String, File> entry : targets.entrySet()) {
            File link = new File(directory, entry.getKey());
            String target = entry.getValue().getAbsolutePath();
            if (fs.kind(link) == Kind.LINK) {
                if (target.equals(fs.readLink(link))) continue;
                fs.unlink(link);
            }
            fs.symlink(target, link);
        }
    }
}
