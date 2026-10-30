package com.termux.ui.desktop.archive;

import com.termux.ui.domain.DesktopDistro;
import java.io.File;

public final class DesktopArchiveImportResult {
    final File containerDirectory;
    final File containersRoot;
    final String receipt;
    final DesktopArchiveMetadata metadata;
    final DesktopDistro desktop;
    final long device,inode;
    DesktopArchiveImportResult(File root, File directory, String receipt,
                               DesktopArchiveMetadata metadata, DesktopDistro desktop,
                               DesktopArchiveFileSystem.Stat stat) {
        this.containersRoot=root; this.containerDirectory=directory; this.receipt=receipt;
        this.metadata=metadata; this.desktop=desktop;
        this.device=stat.device;this.inode=stat.inode;
    }
    public File getContainerDirectory() { return containerDirectory; }
    public DesktopDistro getDesktop() { return desktop; }
    public String getTouchMode() { return metadata.getTouchMode(); }
    public String getOrientation() { return metadata.getOrientation(); }
}
