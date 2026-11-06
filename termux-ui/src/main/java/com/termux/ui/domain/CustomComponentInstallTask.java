package com.termux.ui.domain;

import com.termux.ui.wine.domain.RuntimeTranslator;
import com.termux.ui.wine.domain.GameRuntimeBackendType;

/** Tracks one run of install_custom_rootfs_component.sh -- deliberately carries componentId/kind/
 *  translator/displayName/sha256 on the task record itself (not just at enqueue time) so that
 *  CustomComponentInstallForegroundService can reconstruct and save the resulting
 *  CustomRuntimeComponent registry entry on SUCCEEDED even across a process restart, without a
 *  second in-memory-only side channel. */
public final class CustomComponentInstallTask {
    public static final int SCHEMA_VERSION = 2;

    private final GameRuntimeBackendType backendType;
    private final String taskId;
    private final String componentId;
    private final CustomRuntimeComponent.Kind kind;
    private final RuntimeTranslator translator;
    private final String displayName;
    private final String sha256;
    private final CustomComponentInstallTaskState state;
    private final String errorCode;
    private final long createdAt;
    private final long updatedAt;

    public CustomComponentInstallTask(String taskId, String componentId,
            CustomRuntimeComponent.Kind kind, RuntimeTranslator translator, String displayName,
            String sha256, CustomComponentInstallTaskState state, String errorCode,
            long createdAt, long updatedAt) {
        this(taskId, componentId, kind, translator, displayName, sha256, state, errorCode,
            createdAt, updatedAt, CustomRuntimeComponent.legacyBackend(kind, translator));
    }

    public CustomComponentInstallTask(String taskId, String componentId,
            CustomRuntimeComponent.Kind kind, RuntimeTranslator translator, String displayName,
            String sha256, CustomComponentInstallTaskState state, String errorCode,
            long createdAt, long updatedAt, GameRuntimeBackendType backendType) {
        this.taskId = requireId(taskId);
        if (state == null || createdAt < 0 || updatedAt < createdAt)
            throw new IllegalArgumentException("invalid custom component install task");
        CustomRuntimeComponent.validateTarget(kind, translator, backendType);
        this.backendType = backendType;
        this.componentId = requireId(componentId);
        this.kind = kind;
        this.translator = translator;
        if (displayName == null || displayName.trim().isEmpty() || displayName.length() > 128) {
            throw new IllegalArgumentException("invalid displayName");
        }
        this.displayName = displayName.trim();
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("invalid sha256");
        }
        this.sha256 = sha256;
        this.state = state;
        this.errorCode = errorCode == null ? "" : errorCode;
        if (!this.errorCode.isEmpty() && !this.errorCode.matches("[a-z0-9_:.+-]{1,128}")) {
            throw new IllegalArgumentException("invalid custom install errorCode");
        }
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static CustomComponentInstallTask queued(String taskId, String componentId,
            CustomRuntimeComponent.Kind kind, RuntimeTranslator translator, String displayName,
            String sha256, long now) {
        return new CustomComponentInstallTask(taskId, componentId, kind, translator, displayName,
            sha256, CustomComponentInstallTaskState.QUEUED, "", now, now);
    }

    public static CustomComponentInstallTask queued(String taskId, String componentId,
            CustomRuntimeComponent.Kind kind, RuntimeTranslator translator, String displayName,
            String sha256, long now, GameRuntimeBackendType backendType) {
        return new CustomComponentInstallTask(taskId, componentId, kind, translator, displayName,
            sha256, CustomComponentInstallTaskState.QUEUED, "", now, now, backendType);
    }

    public CustomComponentInstallTask transition(CustomComponentInstallTaskState next,
            String error, long now) {
        if (state.isTerminal()) throw new IllegalStateException("terminal custom install task");
        if (next == null || next == CustomComponentInstallTaskState.QUEUED || now < updatedAt) {
            throw new IllegalArgumentException("invalid custom install transition");
        }
        return new CustomComponentInstallTask(taskId, componentId, kind, translator, displayName,
            sha256, next, error, createdAt, now, backendType);
    }

    public GameRuntimeBackendType getBackendType() { return backendType; }
    public String getTaskId() { return taskId; }
    public String getComponentId() { return componentId; }
    public CustomRuntimeComponent.Kind getKind() { return kind; }
    public RuntimeTranslator getTranslator() { return translator; }
    public String getDisplayName() { return displayName; }
    public String getSha256() { return sha256; }
    public CustomComponentInstallTaskState getState() { return state; }
    public String getErrorCode() { return errorCode; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    private static String requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid id");
        }
        return value;
    }
}
