package com.termux.ui.desktop.archive;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** All inspection is lstat-based. Implementations must never traverse a symbolic-link parent. */
public interface DesktopArchiveFileSystem {
    /** Output for a file atomically created with exclusive-create semantics. */
    public abstract static class ExclusiveOutput extends OutputStream {
        public abstract Stat getIdentity();
        public abstract void sync() throws IOException;
    }
    enum Kind { FILE, DIRECTORY, SYMLINK, SOCKET, FIFO, OTHER }
    final class Stat {
        public final Kind kind;
        public final int mode;
        public final long size, modifiedMillis, device, inode, linkCount;
        public Stat(Kind kind, int mode, long size, long modifiedMillis,
                    long device, long inode, long linkCount) {
            this.kind=kind; this.mode=mode; this.size=size; this.modifiedMillis=modifiedMillis;
            this.device=device; this.inode=inode; this.linkCount=linkCount;
        }
        boolean sameFile(Stat other) {
            return other != null && kind == other.kind && mode == other.mode && size == other.size
                && modifiedMillis == other.modifiedMillis && device == other.device && inode == other.inode;
        }
    }
    /** null only for ENOENT; other failures must be reported. */
    Stat lstat(File file) throws IOException;
    InputStream openRegular(File file, Stat expected) throws IOException;
    /** Creates the final target once; an existing path MUST fail without changing it. */
    ExclusiveOutput createExclusiveFile(File target) throws IOException;
    String readLink(File file) throws IOException;
    void createSymbolicLink(String target, File link) throws IOException;
    void createHardLink(File target, File link) throws IOException;
    void applyMode(File file, int mode) throws IOException;
    void applyModifiedTime(File file, long modifiedMillis) throws IOException;
    /** Atomic no-replace publication. An existing destination, including a race, MUST fail. */
    void rename(File source, File destination) throws IOException;
}
