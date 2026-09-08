package com.termux.localgames.domain;

/** Immutable state for an on-device Termux runtime build. */
public final class RuntimeProvisionTask {
    public static final int SCHEMA_VERSION = 1;

    private final String taskId;
    private final String packageName;
    private final int version;
    private final String recipeSha256;
    private final String sourceComponentId;
    private final String containerName;
    private final RuntimeProvisionTaskState state;
    private final String errorCode;
    private final long createdAt;
    private final long updatedAt;

    public RuntimeProvisionTask(String taskId, String packageName, int version,
                                String recipeSha256, String sourceComponentId,
                                String containerName, RuntimeProvisionTaskState state,
                                String errorCode, long createdAt, long updatedAt) {
        this.taskId = requireId(taskId, "taskId");
        this.packageName = requireId(packageName, "packageName");
        this.sourceComponentId = requireId(sourceComponentId, "sourceComponentId");
        this.containerName = requireId(containerName, "containerName");
        if (version < 1 || state == null || createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid runtime provision task");
        }
        if (recipeSha256 == null || !recipeSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid recipeSha256");
        }
        this.version = version;
        this.recipeSha256 = recipeSha256;
        this.state = state;
        this.errorCode = errorCode == null ? "" : errorCode;
        if (!this.errorCode.isEmpty() && !this.errorCode.matches("[a-z0-9_:.+-]{1,128}")) {
            throw new IllegalArgumentException("invalid provision errorCode");
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static RuntimeProvisionTask queued(String taskId, String packageName, int version,
                                              String recipeSha256, String sourceComponentId,
                                              String containerName, long now) {
        return new RuntimeProvisionTask(taskId, packageName, version, recipeSha256,
            sourceComponentId, containerName, RuntimeProvisionTaskState.QUEUED, "", now, now);
    }

    public RuntimeProvisionTask transition(RuntimeProvisionTaskState next, String error, long now) {
        if (state.isTerminal()) throw new IllegalStateException("terminal provision task");
        if (next == null || next == RuntimeProvisionTaskState.QUEUED || now < updatedAt) {
            throw new IllegalArgumentException("invalid provision transition");
        }
        return new RuntimeProvisionTask(taskId, packageName, version, recipeSha256,
            sourceComponentId, containerName, next, error, createdAt, now);
    }

    public String getTaskId() { return taskId; }
    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getRecipeSha256() { return recipeSha256; }
    public String getSourceComponentId() { return sourceComponentId; }
    public String getContainerName() { return containerName; }
    public RuntimeProvisionTaskState getState() { return state; }
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
