package com.termux.localgames.domain;

/** Immutable state for a user-triggered base-environment backup or restore -- deliberately not
 *  RuntimeSetupTask: that class's validation is tightly bound to "build from a recipe/component
 *  set" semantics (a real recipeSha256, sourceComponentId, containerId) that backup/restore has
 *  no equivalent of. A backup/restore task only ever operates on the one already-published
 *  shared RootFS and the one external backup file -- see backup_restore_rootfs.sh. */
public final class RootfsBackupTask {
    public static final int SCHEMA_VERSION = 1;

    public enum Kind { BACKUP, RESTORE }

    private final String taskId;
    private final Kind kind;
    private final RootfsBackupTaskState state;
    private final String errorCode;
    private final long createdAt;
    private final long updatedAt;

    public RootfsBackupTask(String taskId, Kind kind, RootfsBackupTaskState state,
                            String errorCode, long createdAt, long updatedAt) {
        this.taskId = requireId(taskId);
        if (kind == null || state == null || createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid rootfs backup task");
        }
        this.kind = kind;
        this.state = state;
        this.errorCode = errorCode == null ? "" : errorCode;
        if (!this.errorCode.isEmpty() && !this.errorCode.matches("[a-z0-9_:.+-]{1,128}")) {
            throw new IllegalArgumentException("invalid backup errorCode");
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static RootfsBackupTask queued(String taskId, Kind kind, long now) {
        return new RootfsBackupTask(taskId, kind, RootfsBackupTaskState.QUEUED, "", now, now);
    }

    public RootfsBackupTask transition(RootfsBackupTaskState next, String error, long now) {
        if (state.isTerminal()) throw new IllegalStateException("terminal backup task");
        if (next == null || next == RootfsBackupTaskState.QUEUED || now < updatedAt) {
            throw new IllegalArgumentException("invalid backup transition");
        }
        return new RootfsBackupTask(taskId, kind, next, error, createdAt, now);
    }

    public String getTaskId() { return taskId; }
    public Kind getKind() { return kind; }
    public RootfsBackupTaskState getState() { return state; }
    public String getErrorCode() { return errorCode; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    private static String requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid taskId");
        }
        return value;
    }
}
