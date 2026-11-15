package com.termux.ui.platform;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

/** Robolectric 4.10's SDK-28 lstat stub does not report missing files or symlink modes. */
@Implements(Os.class)
public class StorageTestOs {
    @Implementation public static StructStat lstat(String path) throws ErrnoException {
        try {
            Path file = Paths.get(path);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new java.nio.file.NoSuchFileException(path);
            // Robolectric uses synthetic S_IF* values; don't mix them with the host's Linux bits.
            int mode = Files.isSymbolicLink(file) ? OsConstants.S_IFLNK
                : Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS) ? OsConstants.S_IFDIR : OsConstants.S_IFREG;
            android.system.StructTimespec time = new android.system.StructTimespec(0, 0);
            return new StructStat(1, 1, mode, 1, 0, 0, 0, 0, time, time, time, 4096, 0);
        } catch (IOException error) { throw new ErrnoException("lstat", OsConstants.ENOENT, error); }
    }
    @Implementation public static String readlink(String path) throws ErrnoException {
        try { return Files.readSymbolicLink(Paths.get(path)).toString(); }
        catch (IOException error) { throw new ErrnoException("readlink", OsConstants.EINVAL, error); }
    }
    @Implementation public static void symlink(String target, String path) throws ErrnoException {
        try { Files.createSymbolicLink(Paths.get(path), Paths.get(target)); }
        catch (IOException error) { throw new ErrnoException("symlink", OsConstants.EEXIST, error); }
    }
    @Implementation public static void remove(String path) throws ErrnoException {
        try { Files.delete(Paths.get(path)); }
        catch (IOException error) { throw new ErrnoException("remove", OsConstants.ENOENT, error); }
    }
}
