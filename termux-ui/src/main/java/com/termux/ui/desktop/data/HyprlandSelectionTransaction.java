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

    public HyprlandSelectionTransaction(File directory, DesktopDistroRepository saved) {
        this.directory = directory;
        this.saved = saved;
        this.candidates = new FileDesktopDistroRepository(directory, new File(directory, "unused-home"));
    }

    private File receipt(String id) {
        if (!DesktopDistro.isValidId(id)) throw new IllegalArgumentException("invalidDesktopId");
        return new File(directory, id + ".task");
    }

    public void stage(DesktopDistro candidate, String taskId) throws IOException {
        if (candidate.getDe() != DesktopDe.HYPRLAND) throw new IOException("hyprland_selection_required");
        discard(candidate.getId());
        candidates.save(candidate);
        DesktopInstallState.writeTask(receipt(candidate.getId()), taskId);
    }

    public void finish(String id, String taskId, boolean success, Predicate<DesktopDistro> installed) throws IOException {
        String expected = DesktopInstallState.readTask(receipt(id));
        Optional<DesktopDistro> candidate = candidates.find(id);
        if (success && taskId.equals(expected) && candidate.isPresent() && installed.test(candidate.get())) {
            saved.save(candidate.get());
        }
        discard(id);
    }

    public void discard(String id) throws IOException {
        File receipt = receipt(id);
        if (receipt.exists() && !receipt.delete()) throw new IOException("hyprland_candidate_cleanup_failed");
        candidates.delete(id);
    }
}
