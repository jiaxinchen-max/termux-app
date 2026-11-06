package com.termux.ui.wine.recovery;

import android.system.*;
import android.os.ParcelFileDescriptor;
import com.termux.shared.file.AtomicFilePublisher;
import java.io.*;

/** No-follow reads, exclusive output creation and no-replace publication. */
public final class AndroidWineCDriveFileSystem implements WineCDriveArchive.FileSystem {
    @Override public int kind(File file) throws IOException {
        try {
            int mode = Os.lstat(file.getPath()).st_mode;
            if (OsConstants.S_ISREG(mode)) return 1;
            if (OsConstants.S_ISDIR(mode)) return 2;
            if (OsConstants.S_ISLNK(mode)) return 3;
            throw new IOException("cdrive_file_type");
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ENOENT) return 0;
            throw new IOException("cdrive_stat_failed", error);
        }
    }
    @Override public int mode(File file) throws IOException {
        try { return Os.lstat(file.getPath()).st_mode & 0777; }
        catch (ErrnoException error) { throw new IOException("cdrive_stat_failed", error); }
    }
    @Override public InputStream read(File file) throws IOException {
        ParcelFileDescriptor descriptor = open(file, OsConstants.O_RDONLY | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC);
        try {
            if (!OsConstants.S_ISREG(Os.fstat(descriptor.getFileDescriptor()).st_mode)) throw new IOException("cdrive_not_regular");
            return new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
        } catch (ErrnoException | IOException error) { descriptor.close(); throw new IOException("cdrive_read_failed", error); }
    }
    @Override public WineCDriveArchive.OwnedOutput create(File file) throws IOException {
            ParcelFileDescriptor descriptor = open(file, OsConstants.O_WRONLY | OsConstants.O_CREAT
                | OsConstants.O_EXCL | OsConstants.O_NOFOLLOW | OsConstants.O_CLOEXEC);
            String identity;
            try { StructStat stat = Os.fstat(descriptor.getFileDescriptor()); identity = stat.st_dev + ":" + stat.st_ino; }
            catch (ErrnoException error) { descriptor.close(); throw new IOException("cdrive_stat_failed", error); }
            ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
            return new WineCDriveArchive.OwnedOutput() {
                @Override public String identity() { return identity; }
                @Override public void write(int value) throws IOException { output.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { output.write(bytes, offset, length); }
                @Override public void flush() throws IOException { output.flush(); }
                @Override public void close() throws IOException {
                    try { output.flush(); output.getFD().sync(); } finally { output.close(); }
                }
            };
    }
    @Override public String identity(File file) throws IOException {
        try { StructStat stat = Os.lstat(file.getPath()); return stat.st_dev + ":" + stat.st_ino; }
        catch (ErrnoException error) { throw new IOException("cdrive_stat_failed", error); }
    }
    private ParcelFileDescriptor open(File file, int flags) throws IOException {
        FileDescriptor original = null;
        try { original = Os.open(file.getPath(), flags, 0600); return ParcelFileDescriptor.dup(original); }
        catch (ErrnoException error) { throw new IOException("cdrive_open_failed", error); }
        finally { if (original != null) try { Os.close(original); } catch (ErrnoException error) { throw new IOException("cdrive_close_failed", error); } }
    }
    @Override public String readLink(File file) throws IOException {
        try { return Os.readlink(file.getPath()); }
        catch (ErrnoException error) { throw new IOException("cdrive_link_failed", error); }
    }
    @Override public void link(String target, File file) throws IOException {
        try { Os.symlink(target, file.getPath()); }
        catch (ErrnoException error) { throw new IOException("cdrive_link_failed", error); }
    }
    @Override public void chmod(File file, int mode) throws IOException {
        try { Os.chmod(file.getPath(), mode); }
        catch (ErrnoException error) { throw new IOException("cdrive_mode_failed", error); }
    }
    @Override public void move(File source, File target) throws IOException { AtomicFilePublisher.moveNoReplace(source, target); }
}
