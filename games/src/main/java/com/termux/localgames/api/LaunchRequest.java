package com.termux.localgames.api;

import com.termux.localgames.domain.LaunchExecutionMode;

import java.io.File;

/** Validated private-file request for one Termux-backed game launch. */
public final class LaunchRequest {

    private final String taskId;
    private final String scriptPath;
    private final String specPath;
    private final String workingDirectory;
    private final LaunchExecutionMode executionMode;

    public LaunchRequest(String taskId, String scriptPath, String specPath,
                         String workingDirectory, LaunchExecutionMode executionMode) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid taskId");
        }
        if (executionMode == null) throw new IllegalArgumentException("executionMode required");
        this.taskId = taskId;
        this.scriptPath = requireAbsolute(scriptPath, "scriptPath");
        this.specPath = requireAbsolute(specPath, "specPath");
        this.workingDirectory = requireAbsolute(workingDirectory, "workingDirectory");
        this.executionMode = executionMode;
    }

    private static String requireAbsolute(String value, String field) {
        if (value == null || !new File(value).isAbsolute() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must be absolute");
        }
        return value;
    }

    public String getTaskId() { return taskId; }
    public String getScriptPath() { return scriptPath; }
    public String getSpecPath() { return specPath; }
    public String getWorkingDirectory() { return workingDirectory; }
    public LaunchExecutionMode getExecutionMode() { return executionMode; }
    public String getShellName() { return "local-games-" + taskId; }
}
