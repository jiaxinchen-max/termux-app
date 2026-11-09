package com.termux.ui.files;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Single-item browser operations. Never follows links while copying or deleting a tree. */
public final class BrowserFileOperations {
    public interface Links {
        boolean isLink(File file) throws IOException;
        String read(File file) throws IOException;
        void create(String target, File file) throws IOException;
    }

    private static final Links ANDROID_LINKS = new Links() {
        public boolean isLink(File file) throws IOException {
            try { return OsConstants.S_ISLNK(Os.lstat(file.getPath()).st_mode); }
            catch (ErrnoException e) {
                if (e.errno == OsConstants.ENOENT) return false;
                throw new IOException(e);
            }
        }
        public String read(File file) throws IOException {
            try { return Os.readlink(file.getPath()); } catch (ErrnoException e) { throw new IOException(e); }
        }
        public void create(String target, File file) throws IOException {
            try { Os.symlink(target, file.getPath()); } catch (ErrnoException e) { throw new IOException(e); }
        }
    };

    private final File[] roots;
    private final Links links;

    public BrowserFileOperations(File... roots) { this(ANDROID_LINKS, roots); }

    public BrowserFileOperations(Links links, File... roots) {
        this.roots = roots.clone();
        this.links = links;
    }

    public boolean canOperate(File file) {
        try { source(file); return true; } catch (IOException | SecurityException e) { return false; }
    }

    private File source(File file) throws IOException {
        File path = scoped(file);
        File resolved = path.getCanonicalFile();
        for (File root : roots) {
            // An ancestor of the nested Games root cannot be recursively removed either.
            if (contains(resolved, root.getCanonicalFile())) throw failure("protected_root", path);
        }
        if (!path.exists() && !links.isLink(path)) throw failure("source_missing", path);
        return path;
    }

    private File scoped(File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent == null) throw failure("outside_roots", file);
        File path = new File(parent.getCanonicalFile(), file.getName());
        if (".".equals(file.getName()) || "..".equals(file.getName())) throw failure("invalid_path", file);
        for (File root : roots) if (contains(root.getCanonicalFile(), path)) return path;
        throw failure("outside_roots", path);
    }

    public File paste(File original, File destination, boolean cut) throws IOException {
        File source = source(original);
        File parent = destination.getCanonicalFile();
        scoped(parent);
        if (!parent.isDirectory()) throw failure("destination_missing", parent);
        File target = new File(parent, source.getName());
        if (source.equals(target) || (!links.isLink(source) && contains(source.getCanonicalFile(), parent))) {
            throw failure("destination_inside_source", target);
        }
        if (target.exists() || links.isLink(target)) throw failure("destination_exists", target);
        File stage = new File(parent, ".file-transfer-" + UUID.randomUUID());
        boolean published = false;
        Throwable failure = null;
        try {
            copy(source, stage);
            checkInterrupted();
            if (target.exists() || links.isLink(target)) throw failure("destination_exists", target);
            // Claim the final name exclusively: never rename over an existing user item.
            boolean directory = !links.isLink(stage) && stage.isDirectory();
            if (directory) {
                if (!target.mkdir()) throw failure("destination_exists", target);
            } else if (!target.createNewFile()) throw failure("destination_exists", target);
            try {
                if (!stage.renameTo(target)) throw failure("publish_failed", target);
            } catch (IOException e) {
                if (!target.delete()) e.addSuppressed(failure("cleanup_failed", target));
                throw e;
            }
            published = true;
            if (cut) {
                try { delete(source); }
                catch (IOException e) { throw new IOException("move_source_delete_failed: " + source + "; destination=" + target, e); }
            }
            return target;
        } catch (IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            if (!published && (stage.exists() || links.isLink(stage))) {
                boolean interrupted = Thread.interrupted();
                try { erase(stage); }
                catch (IOException | RuntimeException cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
                } finally { if (interrupted) Thread.currentThread().interrupt(); }
            }
        }
    }

    public void delete(File file) throws IOException {
        File checked = source(file);
        try { erase(checked); }
        catch (IOException e) { throw new IOException("delete_failed_may_be_partial: " + checked, e); }
    }

    private void copy(File source, File target) throws IOException {
        checkInterrupted();
        if (links.isLink(source)) { links.create(links.read(source), target); return; }
        if (source.isDirectory()) {
            if (!target.mkdir()) throw failure("create_directory_failed", target);
            File[] children = source.listFiles();
            if (children == null) throw failure("read_directory_failed", source);
            for (File child : children) copy(child, new File(target, child.getName()));
        } else if (source.isFile()) {
            if (!target.createNewFile()) throw failure("destination_exists", target);
            try (FileInputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) != -1) { checkInterrupted(); output.write(buffer, 0, count); }
                output.getFD().sync();
            }
            // Shared-storage filesystems may not represent Unix execute permissions.
            if (source.canExecute()) target.setExecutable(true, true);
        } else throw failure("unsupported_file", source);
    }

    private void erase(File file) throws IOException {
        checkInterrupted();
        if (!links.isLink(file) && file.isDirectory()) {
            File[] children = file.listFiles();
            if (children == null) throw failure("read_directory_failed", file);
            for (File child : children) erase(child);
        }
        if (!file.delete()) throw failure("delete_failed_may_be_partial", file);
    }

    public static boolean contains(File root, File child) {
        return root.equals(child) || child.getPath().startsWith(root.getPath() + File.separator);
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("operation_interrupted");
    }

    private static IOException failure(String reason, File file) { return new IOException(reason + ": " + file); }
}
