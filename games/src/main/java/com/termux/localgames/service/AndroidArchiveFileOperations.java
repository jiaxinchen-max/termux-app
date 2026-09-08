package com.termux.localgames.service;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import com.termux.localgames.components.install.ArchiveFileOperations;

import java.io.File;
import java.io.IOException;

final class AndroidArchiveFileOperations implements ArchiveFileOperations {

    @Override
    public void createSymbolicLink(String target, File link) throws IOException {
        try {
            Os.symlink(target, link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("Unable to create symbolic link", e);
        }
    }

    @Override
    public void createHardLink(File target, File link) throws IOException {
        try {
            Os.link(target.getAbsolutePath(), link.getAbsolutePath());
        } catch (ErrnoException e) {
            throw new IOException("Unable to create hard link", e);
        }
    }

    @Override
    public void applyMode(File file, int mode) throws IOException {
        try {
            Os.chmod(file.getAbsolutePath(), mode & 0777);
        } catch (ErrnoException e) {
            throw new IOException("Unable to apply archive mode", e);
        }
    }

    @Override
    public boolean isSymbolicLink(File file) throws IOException {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.ENOENT) return false;
            throw new IOException("Unable to inspect archive path", e);
        }
    }
}
