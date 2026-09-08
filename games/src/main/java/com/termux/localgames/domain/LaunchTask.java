package com.termux.localgames.domain;

/** Immutable snapshot of one persisted game launch attempt. */
public final class LaunchTask {
    public static final int SCHEMA_VERSION = 3;

    private final String taskId;
    private final String gameId;
    private final LaunchTaskState state;
    private final LaunchStage stage;
    private final int progress;
    private final long startedAt;
    private final long pid;
    private final Integer exitCode;
    private final String errorCode;
    private final boolean recoverable;
    private final String logRef;
    private final long lastEventSequence;
    private final long updatedAt;
    private final String environmentFingerprint;
    private final boolean displayConnected;
    private final long displayUpdatedAt;
    private final long firstFrameAt;

    public LaunchTask(String taskId, String gameId, LaunchTaskState state,
                      LaunchStage stage, int progress, long startedAt, long pid,
                      Integer exitCode, String errorCode, boolean recoverable,
                      String logRef) {
        this(taskId, gameId, state, stage, progress, startedAt, pid, exitCode,
            errorCode, recoverable, logRef, 0, startedAt, "", false, 0, 0);
    }

    public LaunchTask(String taskId, String gameId, LaunchTaskState state,
                      LaunchStage stage, int progress, long startedAt, long pid,
                      Integer exitCode, String errorCode, boolean recoverable,
                      String logRef, long lastEventSequence, long updatedAt,
                      String environmentFingerprint) {
        this(taskId, gameId, state, stage, progress, startedAt, pid, exitCode,
            errorCode, recoverable, logRef, lastEventSequence, updatedAt,
            environmentFingerprint, false, 0, 0);
    }

    public LaunchTask(String taskId, String gameId, LaunchTaskState state,
                      LaunchStage stage, int progress, long startedAt, long pid,
                      Integer exitCode, String errorCode, boolean recoverable,
                      String logRef, long lastEventSequence, long updatedAt,
                      String environmentFingerprint, boolean displayConnected,
                      long displayUpdatedAt, long firstFrameAt) {
        this.taskId = DomainValidation.requireText(taskId, "taskId");
        this.gameId = DomainValidation.requireText(gameId, "gameId");
        if (state == null || stage == null) throw new IllegalArgumentException("state and stage must not be null");
        this.state = state;
        this.stage = stage;
        this.progress = DomainValidation.requireProgress(progress);
        if (startedAt < 0 || updatedAt < startedAt || pid == 0 || pid < -1 || lastEventSequence < 0) {
            throw new IllegalArgumentException("invalid startedAt or pid");
        }
        if (state == LaunchTaskState.SUCCEEDED &&
            (progress != 100 || exitCode == null || exitCode != 0)) {
            throw new IllegalArgumentException("succeeded task requires 100 progress and exit code 0");
        }
        if (state == LaunchTaskState.FAILED &&
            (errorCode == null || errorCode.trim().isEmpty())) {
            throw new IllegalArgumentException("failed task requires errorCode");
        }
        this.startedAt = startedAt;
        this.pid = pid;
        this.exitCode = exitCode;
        this.errorCode = DomainValidation.optionalText(errorCode);
        this.recoverable = recoverable;
        this.logRef = DomainValidation.optionalText(logRef);
        this.lastEventSequence = lastEventSequence;
        this.updatedAt = updatedAt;
        this.environmentFingerprint = DomainValidation.optionalText(environmentFingerprint);
        if (!this.environmentFingerprint.isEmpty() &&
            !this.environmentFingerprint.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid environmentFingerprint");
        }
        if (displayUpdatedAt < 0 || firstFrameAt < 0 ||
            (displayUpdatedAt > 0 && (displayUpdatedAt < startedAt || displayUpdatedAt > updatedAt)) ||
            (firstFrameAt > 0 && (firstFrameAt < startedAt || firstFrameAt > updatedAt))) {
            throw new IllegalArgumentException("invalid display evidence timestamp");
        }
        if (stage == LaunchStage.RUNNING && firstFrameAt == 0) {
            throw new IllegalArgumentException("running stage requires first frame");
        }
        this.displayConnected = displayConnected;
        this.displayUpdatedAt = displayUpdatedAt;
        this.firstFrameAt = firstFrameAt;
    }

    public static LaunchTask queued(String taskId, String gameId, long startedAt) {
        return new LaunchTask(taskId, gameId, LaunchTaskState.QUEUED,
            LaunchStage.QUEUED, 0, startedAt, -1, null, "", false, "");
    }

    public LaunchTask transition(LaunchTaskState nextState, LaunchStage nextStage,
                                 int nextProgress, long nextPid, Integer nextExitCode,
                                 String nextErrorCode, boolean nextRecoverable,
                                 String nextLogRef) {
        if (state.isTerminal()) throw new IllegalStateException("terminal launch task cannot transition");
        if (nextState == LaunchTaskState.QUEUED && state != LaunchTaskState.QUEUED) {
            throw new IllegalArgumentException("launch task cannot return to queued");
        }
        if (nextProgress < progress) throw new IllegalArgumentException("launch progress must not decrease");
        return copy(nextState, nextStage, nextProgress, nextPid, nextExitCode,
            nextErrorCode, nextRecoverable, nextLogRef, lastEventSequence, updatedAt,
            displayConnected, displayUpdatedAt, firstFrameAt);
    }

    public LaunchTask applyEvent(LaunchTaskState nextState, LaunchStage nextStage,
                                 int nextProgress, long nextPid, Integer nextExitCode,
                                 String nextErrorCode, boolean nextRecoverable,
                                 String nextLogRef, long eventSequence, long eventTimestamp) {
        if (eventSequence <= lastEventSequence) throw new IllegalArgumentException("launch event sequence must increase");
        if (eventTimestamp < updatedAt) throw new IllegalArgumentException("launch event timestamp must not decrease");
        LaunchTask transitioned = transition(nextState, nextStage, nextProgress, nextPid,
            nextExitCode, nextErrorCode, nextRecoverable, nextLogRef);
        return copy(transitioned.state, transitioned.stage, transitioned.progress,
            transitioned.pid, transitioned.exitCode, transitioned.errorCode,
            transitioned.recoverable, transitioned.logRef, eventSequence, eventTimestamp,
            displayConnected, displayUpdatedAt, firstFrameAt);
    }

    public LaunchTask withEnvironmentFingerprint(String fingerprint) {
        return withEnvironmentFingerprint(fingerprint, updatedAt);
    }

    public LaunchTask withEnvironmentFingerprint(String fingerprint, long nextUpdatedAt) {
        if (state != LaunchTaskState.QUEUED || lastEventSequence != 0) {
            throw new IllegalStateException("fingerprint can only be frozen before launch");
        }
        if (nextUpdatedAt < updatedAt) throw new IllegalArgumentException("updatedAt must not decrease");
        return new LaunchTask(taskId, gameId, state, stage, progress, startedAt, pid,
            exitCode, errorCode, recoverable, logRef, lastEventSequence, nextUpdatedAt,
            DomainValidation.requireText(fingerprint, "environmentFingerprint"),
            displayConnected, displayUpdatedAt, firstFrameAt);
    }

    public LaunchTask touch(long nextUpdatedAt) {
        if (state.isTerminal() || nextUpdatedAt < updatedAt) throw new IllegalArgumentException("invalid launch task touch");
        return copy(state, stage, progress, pid, exitCode, errorCode, recoverable,
            logRef, lastEventSequence, nextUpdatedAt, displayConnected, displayUpdatedAt, firstFrameAt);
    }

    public LaunchTask withDisplayConnection(boolean connected, long timestamp) {
        if (state.isTerminal()) return this;
        if (timestamp < updatedAt) throw new IllegalArgumentException("display timestamp must not decrease");
        return copy(state, stage, progress, pid, exitCode, errorCode, recoverable,
            logRef, lastEventSequence, timestamp, connected, timestamp, firstFrameAt);
    }

    public LaunchTask withFirstFrame(long timestamp) {
        if (state.isTerminal() || firstFrameAt > 0) return this;
        if (timestamp < updatedAt) throw new IllegalArgumentException("first frame timestamp must not decrease");
        LaunchStage nextStage = stage == LaunchStage.WAITING_FIRST_FRAME ? LaunchStage.RUNNING : stage;
        int nextProgress = nextStage == LaunchStage.RUNNING ? Math.max(progress, 95) : progress;
        return copy(state, nextStage, nextProgress, pid, exitCode, errorCode,
            recoverable, logRef, lastEventSequence, timestamp, true, timestamp, timestamp);
    }

    public LaunchTask promoteToRunningFromFirstFrame() {
        if (state.isTerminal() || stage != LaunchStage.WAITING_FIRST_FRAME || firstFrameAt == 0) return this;
        return copy(state, LaunchStage.RUNNING, Math.max(progress, 95), pid, exitCode,
            errorCode, recoverable, logRef, lastEventSequence, updatedAt,
            displayConnected, displayUpdatedAt, firstFrameAt);
    }

    private LaunchTask copy(LaunchTaskState nextState, LaunchStage nextStage,
                            int nextProgress, long nextPid, Integer nextExitCode,
                            String nextErrorCode, boolean nextRecoverable,
                            String nextLogRef, long nextSequence, long nextUpdatedAt,
                            boolean nextDisplayConnected, long nextDisplayUpdatedAt,
                            long nextFirstFrameAt) {
        return new LaunchTask(taskId, gameId, nextState, nextStage, nextProgress,
            startedAt, nextPid, nextExitCode, nextErrorCode, nextRecoverable,
            nextLogRef, nextSequence, nextUpdatedAt, environmentFingerprint,
            nextDisplayConnected, nextDisplayUpdatedAt, nextFirstFrameAt);
    }

    public String getTaskId() { return taskId; }
    public String getGameId() { return gameId; }
    public LaunchTaskState getState() { return state; }
    public LaunchStage getStage() { return stage; }
    public int getProgress() { return progress; }
    public long getStartedAt() { return startedAt; }
    public long getPid() { return pid; }
    public Integer getExitCode() { return exitCode; }
    public String getErrorCode() { return errorCode; }
    public boolean isRecoverable() { return recoverable; }
    public String getLogRef() { return logRef; }
    public long getLastEventSequence() { return lastEventSequence; }
    public long getUpdatedAt() { return updatedAt; }
    public String getEnvironmentFingerprint() { return environmentFingerprint; }
    public boolean isDisplayConnected() { return displayConnected; }
    public long getDisplayUpdatedAt() { return displayUpdatedAt; }
    public long getFirstFrameAt() { return firstFrameAt; }
}
