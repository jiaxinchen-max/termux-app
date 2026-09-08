package com.termux.localgames.domain;

/** One validated JSONL event emitted by the launch script. */
public final class LaunchEvent {

    public static final int SCHEMA_VERSION = 1;

    private final String taskId;
    private final long sequence;
    private final LaunchTaskState state;
    private final LaunchStage stage;
    private final int progress;
    private final long timestamp;
    private final long pid;
    private final Integer exitCode;
    private final String errorCode;
    private final boolean recoverable;
    private final String message;
    private final String logRef;

    public LaunchEvent(String taskId, long sequence, LaunchTaskState state,
                       LaunchStage stage, int progress, long timestamp, long pid,
                       Integer exitCode, String errorCode, boolean recoverable,
                       String message, String logRef) {
        this.taskId = DomainValidation.requireText(taskId, "taskId");
        if (sequence < 1 || timestamp < 0 || pid == 0 || pid < -1) {
            throw new IllegalArgumentException("invalid launch event identity");
        }
        if (state == null || stage == null) {
            throw new IllegalArgumentException("launch event state and stage required");
        }
        this.sequence = sequence;
        this.state = state;
        this.stage = stage;
        this.progress = DomainValidation.requireProgress(progress);
        this.timestamp = timestamp;
        this.pid = pid;
        this.exitCode = exitCode;
        this.errorCode = DomainValidation.optionalText(errorCode);
        this.recoverable = recoverable;
        this.message = DomainValidation.optionalText(message);
        this.logRef = DomainValidation.optionalText(logRef);
    }

    public String getTaskId() { return taskId; }
    public long getSequence() { return sequence; }
    public LaunchTaskState getState() { return state; }
    public LaunchStage getStage() { return stage; }
    public int getProgress() { return progress; }
    public long getTimestamp() { return timestamp; }
    public long getPid() { return pid; }
    public Integer getExitCode() { return exitCode; }
    public String getErrorCode() { return errorCode; }
    public boolean isRecoverable() { return recoverable; }
    public String getMessage() { return message; }
    public String getLogRef() { return logRef; }
}
