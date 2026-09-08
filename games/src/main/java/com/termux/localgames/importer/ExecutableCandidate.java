package com.termux.localgames.importer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** One PE-validated executable candidate. */
public final class ExecutableCandidate {

    private final String uri;
    private final String relativePath;
    private final String workingDirectory;
    private final long size;
    private final int score;
    private final boolean discouraged;
    private final List<CandidateSignal> signals;

    public ExecutableCandidate(String uri, String relativePath, String workingDirectory,
                               long size, int score, boolean discouraged,
                               List<CandidateSignal> signals) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.relativePath = Objects.requireNonNull(relativePath, "relativePath");
        this.workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        this.size = size;
        this.score = score;
        this.discouraged = discouraged;
        this.signals = Collections.unmodifiableList(new ArrayList<>(signals));
    }

    public String getUri() { return uri; }
    public String getRelativePath() { return relativePath; }
    public String getWorkingDirectory() { return workingDirectory; }
    public long getSize() { return size; }
    public int getScore() { return score; }
    public boolean isDiscouraged() { return discouraged; }
    public List<CandidateSignal> getSignals() { return signals; }
}
