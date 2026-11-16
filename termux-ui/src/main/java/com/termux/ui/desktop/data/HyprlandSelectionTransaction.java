package com.termux.ui.desktop.data;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.runtime.DesktopInstallState;
import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.function.Predicate;

/** A candidate cannot replace the saved selection until its own install task succeeds. */
public final class HyprlandSelectionTransaction {
    private final File directory;
    private final DesktopDistroRepository saved;
    private final FileDesktopDistroRepository candidates;
    private final File anchor;

    public HyprlandSelectionTransaction(File directory, DesktopDistroRepository saved) {
        this(directory, saved, directory.getParentFile());
    }

    public HyprlandSelectionTransaction(File directory, DesktopDistroRepository saved, File anchor) {
        this.directory = directory;
        this.saved = saved;
        this.anchor = anchor;
        this.candidates = new FileDesktopDistroRepository(directory, new File(directory, "unused-home"));
    }

    private File receipt(String id) {
        if (!DesktopDistro.isValidId(id)) throw new IllegalArgumentException("invalidDesktopId");
        return new File(directory, id + ".task");
    }

    public void stage(DesktopDistro candidate, String taskId) throws IOException {
        stage(candidate, taskId, java.util.Collections.emptyList());
    }

    public void stage(DesktopDistro candidate, String taskId, java.util.List<File> managedFiles) throws IOException {
        if (!candidate.getDe().isCreatable()) throw new IOException("desktop_type_retired");
        discard(candidate.getId());
        if (!managedFiles.isEmpty()) backup(candidate.getId()).capture(managedFiles);
        candidates.save(candidate);
        DesktopInstallState.writeTask(receipt(candidate.getId()), taskId);
    }

    public void finish(String id, String taskId, boolean success, Predicate<DesktopDistro> installed) throws IOException {
        String expected = DesktopInstallState.readTask(receipt(id));
        Optional<DesktopDistro> candidate = candidates.find(id);
        if (success && taskId.equals(expected) && candidate.isPresent() && installed.test(candidate.get())) {
            saved.save(candidate.get());
        } else {
            backup(id).restore();
        }
        discard(id);
    }

    public void discard(String id) throws IOException {
        File receipt = receipt(id);
        if (receipt.exists() && !receipt.delete()) throw new IOException("hyprland_candidate_cleanup_failed");
        candidates.delete(id);
        backup(id).discard();
    }

    public void rollback(String id) throws IOException { backup(id).restore(); discard(id); }
    private PreparedDesktopBackup backup(String id) {
        receipt(id); // Validate identity before forming a directory name.
        return new PreparedDesktopBackup(new File(directory, id + ".rollback"), anchor);
    }
}
