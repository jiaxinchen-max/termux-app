package com.termux.localgames.domain;

/** Persistent state for initializing one game-owned GLIBC Wine prefix. */
public final class PrefixProvisionTask {
    public static final int SCHEMA_VERSION = 1;

    private final String taskId;
    private final String gameId;
    private final String winePackage;
    private final RuntimeProvisionTaskState state;
    private final String errorCode;
    private final long createdAt;
    private final long updatedAt;

    public PrefixProvisionTask(String taskId, String gameId, String winePackage,
                               RuntimeProvisionTaskState state, String errorCode,
                               long createdAt, long updatedAt) {
        this.taskId = requireId(taskId, "taskId");
        this.gameId = requireId(gameId, "gameId");
        this.winePackage = requireId(winePackage, "winePackage");
        if (state == null || createdAt < 0 || updatedAt < createdAt) {
            throw new IllegalArgumentException("invalid prefix provision task");
        }
        this.state = state;
        this.errorCode = errorCode == null ? "" : errorCode;
        if (!this.errorCode.isEmpty() &&
            !this.errorCode.matches("[a-z0-9_:.+-]{1,128}")) {
            throw new IllegalArgumentException("invalid prefix provision errorCode");
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static PrefixProvisionTask queued(String taskId, String gameId,
                                              String winePackage, long now) {
        return new PrefixProvisionTask(taskId, gameId, winePackage,
            RuntimeProvisionTaskState.QUEUED, "", now, now);
    }

    public PrefixProvisionTask transition(RuntimeProvisionTaskState next, String error, long now) {
        if (state.isTerminal()) throw new IllegalStateException("terminal prefix provision task");
        if (next == null || next == RuntimeProvisionTaskState.QUEUED || now < updatedAt) {
            throw new IllegalArgumentException("invalid prefix provision transition");
        }
        return new PrefixProvisionTask(taskId, gameId, winePackage, next, error,
            createdAt, now);
    }

    public String getTaskId() { return taskId; }
    public String getGameId() { return gameId; }
    public String getWinePackage() { return winePackage; }
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
