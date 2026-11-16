package com.termux.ui.desktop.archive;

import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.runtime.DesktopLaunchSpec;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Publishes the final launch contract during restoration, before the inventory record. */
public final class DesktopArchiveLaunchSpec {
    private DesktopArchiveLaunchSpec() { }

    public interface Identity {
        void created(File temporary, long device, long inode) throws IOException;
    }

    public static void publish(DesktopArchiveFileSystem fs, File target, DesktopDistro desktop,
                               File pid, File status, File log, Identity journal) throws IOException {
        byte[] content = DesktopLaunchSpec.encode(desktop, pid.getPath(), status.getPath(), log.getPath())
            .getBytes(StandardCharsets.UTF_8);
        File directory = target.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("desktop_spec_directory_failed");
        DesktopArchiveFileSystem.Stat parent = fs.lstat(directory);
        if (parent == null || parent.kind != DesktopArchiveFileSystem.Kind.DIRECTORY)
            throw new IOException("desktop_spec_directory_invalid");
        if (fs.lstat(target) != null) throw new IOException("desktop_spec_already_exists");
        File temporary = new File(directory, target.getName() + ".restore-" + UUID.randomUUID());
        DesktopArchiveFileSystem.Stat owned = null;
        try {
            try (DesktopArchiveFileSystem.ExclusiveOutput output = fs.createExclusiveFile(temporary)) {
                owned = output.getIdentity();
                if (owned == null || owned.kind != DesktopArchiveFileSystem.Kind.FILE)
                    throw new IOException("desktop_spec_identity_invalid");
                journal.created(temporary, owned.device, owned.inode);
                output.write(content);
                output.sync();
            }
            fs.rename(temporary, target);
        } finally {
            if (owned != null) {
                DesktopArchiveFileSystem.Stat current = fs.lstat(temporary);
                if (current != null && (current.kind != DesktopArchiveFileSystem.Kind.FILE
                        || current.device != owned.device || current.inode != owned.inode
                        || !temporary.delete())) throw new IOException("desktop_spec_cleanup_identity_failed");
            }
        }
    }
}
