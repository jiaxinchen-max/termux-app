package com.termux.ui.desktop.archive;

import java.io.IOException;
import java.io.File;

/** Throw IOException from update to cancel. total=-1 means the total is not yet known. */
public interface DesktopArchiveControl {
    void update(String message, long bytes, long total) throws IOException;
    /** Called after exclusive creation and before any archive bytes; persist identity for cleanup. */
    default void createdOutput(File file, long device, long inode) throws IOException { }
    /** Called immediately after staging creation, before any imported payload is written. */
    default void createdImportStaging(File directory, long device, long inode) throws IOException { }
    DesktopArchiveControl NONE = (message, bytes, total) -> { };
}
