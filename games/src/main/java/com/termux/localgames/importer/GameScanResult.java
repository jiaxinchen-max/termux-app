package com.termux.localgames.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class GameScanResult {

    private final String rootName;
    private final List<ExecutableCandidate> candidates;
    private final Set<ScanLimit> reachedLimits;
    private final int scannedDocuments;
    private final int scannedDirectories;
    private final int unreadableExecutables;
    private final long declaredBytes;

    public GameScanResult(String rootName, List<ExecutableCandidate> candidates,
                          Set<ScanLimit> reachedLimits, int scannedDocuments,
                          int scannedDirectories, int unreadableExecutables,
                          long declaredBytes) {
        this.rootName = rootName;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.reachedLimits = reachedLimits.isEmpty()
            ? Collections.emptySet() : Collections.unmodifiableSet(EnumSet.copyOf(reachedLimits));
        this.scannedDocuments = scannedDocuments;
        this.scannedDirectories = scannedDirectories;
        this.unreadableExecutables = unreadableExecutables;
        this.declaredBytes = declaredBytes;
    }

    public String getRootName() { return rootName; }
    public List<ExecutableCandidate> getCandidates() { return candidates; }
    public Set<ScanLimit> getReachedLimits() { return reachedLimits; }
    public int getScannedDocuments() { return scannedDocuments; }
    public int getScannedDirectories() { return scannedDirectories; }
    public int getUnreadableExecutables() { return unreadableExecutables; }
    public long getDeclaredBytes() { return declaredBytes; }
    public boolean isComplete() { return reachedLimits.isEmpty(); }
}
