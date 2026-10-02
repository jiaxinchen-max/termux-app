package com.termux.localgames.domain;

/** Immutable state for an on-device Termux runtime build. */
public final class RuntimeSetupTask {
    public static final int SCHEMA_VERSION = 4;

    private final String taskId;
    private final String packageName;
    private final int version;
    private final String recipeSha256;
    private final String sourceComponentId;
    private final String dxComponentId;
    private final String containerId;
    private final String containerName;
    private final boolean baseOnly;
    private final RuntimeSetupTaskState state;
    private final String errorCode;
    private final long createdAt;
    private final long updatedAt;

    public RuntimeSetupTask(String taskId, String packageName, int version,
                                String recipeSha256, String sourceComponentId,
                                String dxComponentId,
                                String containerId, String containerName, boolean baseOnly,
                                RuntimeSetupTaskState state,
                                String errorCode, long createdAt, long updatedAt) {
        this.taskId = requireId(taskId, "taskId");
        this.packageName = requireId(packageName, "packageName");
        this.sourceComponentId = requireId(sourceComponentId, "sourceComponentId");
        this.dxComponentId = requireId(dxComponentId, "dxComponentId");
        this.containerId = requireId(containerId, "containerId");
        this.containerName = requireId(containerName, "containerName");
        if (version < 1 || state == null || createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid runtime setup task");
        }
        if (recipeSha256 == null || !recipeSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid recipeSha256");
        }
        this.version = version;
        this.recipeSha256 = recipeSha256;
        this.baseOnly = baseOnly;
        this.state = state;
        this.errorCode = errorCode == null ? "" : errorCode;
        if (!this.errorCode.isEmpty() && !this.errorCode.matches("[a-z0-9_:.+-]{1,128}")) {
            throw new IllegalArgumentException("invalid setup errorCode");
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static RuntimeSetupTask queued(String taskId, String packageName, int version,
                                              String recipeSha256, String sourceComponentId,
                                              String dxComponentId,
                                              String containerId, String containerName,
                                              boolean baseOnly, long now) {
        return new RuntimeSetupTask(taskId, packageName, version, recipeSha256,
            sourceComponentId, dxComponentId, containerId, containerName, baseOnly,
            RuntimeSetupTaskState.QUEUED, "", now, now);
    }

    public RuntimeSetupTask transition(RuntimeSetupTaskState next, String error, long now) {
        if (state.isTerminal()) throw new IllegalStateException("terminal setup task");
        if (next == null || next == RuntimeSetupTaskState.QUEUED || now < updatedAt) {
            throw new IllegalArgumentException("invalid setup transition");
        }
        return new RuntimeSetupTask(taskId, packageName, version, recipeSha256,
            sourceComponentId, dxComponentId, containerId, containerName, baseOnly, next, error,
            createdAt, now);
    }

    public String getTaskId() { return taskId; }
    public String getPackageName() { return packageName; }
    public int getVersion() { return version; }
    public String getRecipeSha256() { return recipeSha256; }
    public String getSourceComponentId() { return sourceComponentId; }
    public String getDxComponentId() { return dxComponentId; }
    public String getContainerId() { return containerId; }
    public String getContainerName() { return containerName; }
    /** A BASE_ONLY build (re)builds the shared RootFS base archive and never creates or
     *  activates a real game container -- see setup_rootfs_runtime.sh's BASE_ONLY spec flag. */
    public boolean isBaseOnly() { return baseOnly; }
    public RuntimeSetupTaskState getState() { return state; }
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
