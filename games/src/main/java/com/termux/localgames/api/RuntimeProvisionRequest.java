package com.termux.localgames.api;

/** Validated request for one Termux-owned runtime build. */
public final class RuntimeProvisionRequest {
    private final String taskId;
    private final String scriptPath;
    private final String specPath;
    private final String workingDirectory;

    public RuntimeProvisionRequest(String taskId, String scriptPath, String specPath,
                                   String workingDirectory) {
        this.taskId = requireId(taskId);
        this.scriptPath = requirePath(scriptPath, "scriptPath");
        this.specPath = requirePath(specPath, "specPath");
        this.workingDirectory = requirePath(workingDirectory, "workingDirectory");
    }

    public String getTaskId() { return taskId; }
    public String getScriptPath() { return scriptPath; }
    public String getSpecPath() { return specPath; }
    public String getWorkingDirectory() { return workingDirectory; }
    public String getShellName() { return "games-runtime-provision-" + taskId; }

    private static String requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid provision taskId");
        }
        return value;
    }

    private static String requirePath(String value, String field) {
        if (value == null || !value.startsWith("/") || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }
}
