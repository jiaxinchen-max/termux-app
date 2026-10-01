package com.termux.localgames.domain;

/** Immutable state for an on-device request to purge one runtime environment back to
 *  "not installed" (the opposite of a {@link RuntimeSetupTask}). */
public final class ResetTask {
    public static final int SCHEMA_VERSION = 1;

    private final String taskId;
    private final ResetTarget target;
    private final String resetKey;
    private final ResetTaskState state;
    private final String errorCode;
    private final long createdAt;
    private final long updatedAt;

    public ResetTask(String taskId, ResetTarget target, String resetKey,
                     ResetTaskState state, String errorCode, long createdAt, long updatedAt) {
        this.taskId = requireId(taskId, "taskId");
        if (target == null) throw new IllegalArgumentException("target required");
        this.target = target;
        this.resetKey = resetKey == null ? "" : resetKey;
        if (!this.resetKey.isEmpty()) requireId(this.resetKey, "resetKey");
        if (target == ResetTarget.ROOTFS && this.resetKey.isEmpty()) {
            throw new IllegalArgumentException("rootfs_reset_requires_key");
        }
        if (state == null || createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid reset task");
        }
        this.state = state;
        this.errorCode = errorCode == null ? "" : errorCode;
        if (!this.errorCode.isEmpty() && !this.errorCode.matches("[a-z0-9_:.+-]{1,128}")) {
            throw new IllegalArgumentException("invalid reset errorCode");
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ResetTask queued(String taskId, ResetTarget target, String resetKey, long now) {
        return new ResetTask(taskId, target, resetKey, ResetTaskState.QUEUED, "", now, now);
    }

    public ResetTask transition(ResetTaskState next, String error, long now) {
        if (state.isTerminal()) throw new IllegalStateException("terminal reset task");
        if (next == null || next == ResetTaskState.QUEUED || now < updatedAt) {
            throw new IllegalArgumentException("invalid reset transition");
        }
        return new ResetTask(taskId, target, resetKey, next, error, createdAt, now);
    }

    public String getTaskId() { return taskId; }
    public ResetTarget getTarget() { return target; }
    public String getResetKey() { return resetKey; }
    public ResetTaskState getState() { return state; }
    public String getErrorCode() { return errorCode; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    private static String requireId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
