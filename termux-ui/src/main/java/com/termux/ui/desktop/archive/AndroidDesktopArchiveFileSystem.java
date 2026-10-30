package com.termux.ui.desktop.archive;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import android.os.ParcelFileDescriptor;
import com.termux.shared.file.AtomicFilePublisher;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;

public final class AndroidDesktopArchiveFileSystem implements DesktopArchiveFileSystem {
    private static Stat convert(StructStat s) {
        Kind k = OsConstants.S_ISREG(s.st_mode) ? Kind.FILE
            : OsConstants.S_ISDIR(s.st_mode) ? Kind.DIRECTORY
            : OsConstants.S_ISLNK(s.st_mode) ? Kind.SYMLINK
            : OsConstants.S_ISSOCK(s.st_mode) ? Kind.SOCKET
            : OsConstants.S_ISFIFO(s.st_mode) ? Kind.FIFO : Kind.OTHER;
        return new Stat(k, s.st_mode & 07777, s.st_size, s.st_mtime * 1000,
            s.st_dev, s.st_ino, s.st_nlink);
    }
    @Override public Stat lstat(File file) throws IOException {
        try { return convert(Os.lstat(file.getPath())); }
        catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return null;
            throw new IOException("archive_lstat_failed", e);
        }
    }
    @Override public InputStream openRegular(File file, Stat expected) throws IOException {
        FileDescriptor fd = null;
        try {
            fd = Os.open(file.getPath(), OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW
                | OsConstants.O_CLOEXEC, 0);
            Stat actual = convert(Os.fstat(fd));
            if (actual.kind != Kind.FILE || !expected.sameFile(actual))
                throw new DesktopArchiveException("archive_source_changed");
            // AutoCloseInputStream owns the duplicate; the original is always closed below.
            return new ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(fd));
        } catch (ErrnoException e) { throw new IOException("archive_open_failed", e); }
        finally { if (fd != null) try { Os.close(fd); } catch (ErrnoException ignored) { } }
    }
    @Override public ExclusiveOutput createExclusiveFile(File target) throws IOException {
        FileDescriptor original = null;
        ParcelFileDescriptor duplicate = null;
        Stat created = null;
        try {
            original = Os.open(target.getPath(), OsConstants.O_WRONLY | OsConstants.O_CREAT
                | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC, 0600);
            created = convert(Os.fstat(original));
            if (created.kind != Kind.FILE)
                throw new IOException("archive_output_not_regular");
            duplicate = ParcelFileDescriptor.dup(original);
            final ParcelFileDescriptor.AutoCloseOutputStream output =
                new ParcelFileDescriptor.AutoCloseOutputStream(duplicate);
            // Transfer ownership only after the original descriptor has been closed.
            Os.close(original);
            original = null;
            final Stat createdStat = created;
            ExclusiveOutput result = new ExclusiveOutput() {
                @Override public Stat getIdentity() { return createdStat; }
                @Override public void write(int value) throws IOException { output.write(value); }
                @Override public void write(byte[] bytes) throws IOException { output.write(bytes); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    output.write(bytes, offset, length);
                }
                @Override public void flush() throws IOException { output.flush(); }
                @Override public void sync() throws IOException {
                    output.flush();
                    output.getFD().sync();
                }
                @Override public void close() throws IOException { output.close(); }
            };
            duplicate = null;
            return result;
        } catch (ErrnoException e) {
            IOException failure = new IOException("archive_create_exclusive_failed", e);
            cleanupFailedCreation(target, created, failure);
            throw failure;
        } catch (IOException e) {
            cleanupFailedCreation(target, created, e);
            throw e;
        } finally {
            if (duplicate != null) try { duplicate.close(); } catch (IOException ignored) { }
            if (original != null) try { Os.close(original); } catch (ErrnoException ignored) { }
        }
    }
    private static void cleanupFailedCreation(File target, Stat created, IOException failure) {
        // No stat means open either failed or ownership could not be established. Never unlink then.
        if (created == null) return;
        try {
            Stat current = convert(Os.lstat(target.getPath()));
            if (current.kind == Kind.FILE && current.device == created.device
                    && current.inode == created.inode && !target.delete())
                failure.addSuppressed(new IOException("archive_output_cleanup_failed"));
        } catch (ErrnoException cleanupFailure) {
            if (cleanupFailure.errno != OsConstants.ENOENT)
                failure.addSuppressed(new IOException("archive_output_cleanup_failed", cleanupFailure));
        }
    }
    @Override public String readLink(File file) throws IOException {
        try { return Os.readlink(file.getPath()); }
        catch (ErrnoException e) { throw new IOException("archive_readlink_failed", e); }
    }
    @Override public void createSymbolicLink(String target, File link) throws IOException {
        try { Os.symlink(target, link.getPath()); }
        catch (ErrnoException e) { throw new IOException("archive_symlink_failed", e); }
    }
    @Override public void createHardLink(File target, File link) throws IOException {
        try { Os.link(target.getPath(), link.getPath()); }
        catch (ErrnoException e) { throw new IOException("archive_hardlink_failed", e); }
    }
    @Override public void applyMode(File file, int mode) throws IOException {
        try { Os.chmod(file.getPath(), mode & 07777); }
        catch (ErrnoException e) { throw new IOException("archive_chmod_failed", e); }
    }
    @Override public void applyModifiedTime(File file, long value) throws IOException {
        if (!file.setLastModified(value)) throw new IOException("archive_mtime_failed");
    }
    @Override public void rename(File source, File destination) throws IOException {
        AtomicFilePublisher.moveNoReplace(source,destination);
    }
}
