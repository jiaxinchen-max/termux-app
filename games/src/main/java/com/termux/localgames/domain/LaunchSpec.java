package com.termux.localgames.domain;

import java.io.File;
import java.util.List;
import java.util.Map;

/** Immutable, shell-safe snapshot of every value consumed by one launch attempt. */
public final class LaunchSpec {

    public static final int SCHEMA_VERSION = 4;
    public static final int MAX_ARGUMENTS = 256;
    public static final int MAX_ENVIRONMENT = 256;

    private final String taskId;
    private final String gameId;
    private final String gameRootPath;
    private final String executable;
    private final String workingDirectory;
    private final List<String> arguments;
    private final String prefixPath;
    private final String winePackage;
    private final String graphicsDriver;
    private final String dxWrapper;
    private final String audioDriver;
    private final String resolution;
    private final String box64Preset;
    private final String inputProfileId;
    private final LaunchExecutionMode launchExecutionMode;
    private final Map<String, String> environment;
    private final String eventPath;
    private final String logPath;
    private final String lockPath;
    private final String cancelPath;
    private final long timeoutSeconds;
    private final GameRuntimeBackendType runtimeBackendType;
    private final String rootfsPackage;
    private final String runtimeRootPath;

    public LaunchSpec(String taskId, String gameId, String gameRootPath,
                      String executable, String workingDirectory, List<String> arguments,
                      String prefixPath, String winePackage, String graphicsDriver,
                      String dxWrapper, String audioDriver, String resolution, String box64Preset,
                      Map<String, String> environment, String eventPath, String logPath,
                      String lockPath, String cancelPath, long timeoutSeconds) {
        this(taskId, gameId, gameRootPath, executable, workingDirectory, arguments,
            prefixPath, winePackage, graphicsDriver, dxWrapper, audioDriver, resolution,
            box64Preset, "xinput", environment, eventPath, logPath, lockPath, cancelPath,
            timeoutSeconds);
    }

    public LaunchSpec(String taskId, String gameId, String gameRootPath,
                      String executable, String workingDirectory, List<String> arguments,
                      String prefixPath, String winePackage, String graphicsDriver,
                      String dxWrapper, String audioDriver, String resolution, String box64Preset,
                      String inputProfileId, Map<String, String> environment, String eventPath,
                      String logPath, String lockPath, String cancelPath, long timeoutSeconds) {
        this(taskId, gameId, gameRootPath, executable, workingDirectory, arguments,
            prefixPath, winePackage, graphicsDriver, dxWrapper, audioDriver, resolution,
            box64Preset, inputProfileId, LaunchExecutionMode.APP_SHELL, environment,
            eventPath, logPath, lockPath, cancelPath, timeoutSeconds);
    }

    public LaunchSpec(String taskId, String gameId, String gameRootPath,
                      String executable, String workingDirectory, List<String> arguments,
                      String prefixPath, String winePackage, String graphicsDriver,
                      String dxWrapper, String audioDriver, String resolution, String box64Preset,
                      String inputProfileId, LaunchExecutionMode launchExecutionMode,
                      Map<String, String> environment, String eventPath,
                      String logPath, String lockPath, String cancelPath, long timeoutSeconds) {
        this(taskId, gameId, gameRootPath, executable, workingDirectory, arguments,
            prefixPath, winePackage, graphicsDriver, dxWrapper, audioDriver, resolution,
            box64Preset, inputProfileId, launchExecutionMode, environment, eventPath,
            logPath, lockPath, cancelPath, timeoutSeconds,
            GameRuntimeBackendType.GLIBC_TERMUX_BOX, "", "");
    }

    public LaunchSpec(String taskId, String gameId, String gameRootPath,
                      String executable, String workingDirectory, List<String> arguments,
                      String prefixPath, String winePackage, String graphicsDriver,
                      String dxWrapper, String audioDriver, String resolution, String box64Preset,
                      String inputProfileId, LaunchExecutionMode launchExecutionMode,
                      Map<String, String> environment, String eventPath,
                      String logPath, String lockPath, String cancelPath, long timeoutSeconds,
                      GameRuntimeBackendType runtimeBackendType, String rootfsPackage,
                      String runtimeRootPath) {
        this.taskId = requireId(taskId, "taskId");
        this.gameId = requireId(gameId, "gameId");
        this.gameRootPath = requireAbsolute(gameRootPath, "gameRootPath");
        this.executable = requireRelative(executable, false, "executable");
        this.workingDirectory = requireRelative(workingDirectory, true, "workingDirectory");
        this.arguments = DomainValidation.immutableTextList(arguments, "arguments");
        if (this.arguments.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("too many launch arguments");
        }
        for (String argument : this.arguments) requireShellText(argument, "argument");
        this.prefixPath = requireAbsolute(prefixPath, "prefixPath");
        this.winePackage = requireShellText(DomainValidation.requireText(winePackage, "winePackage"), "winePackage");
        if (!this.winePackage.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid winePackage");
        }
        this.graphicsDriver = requireShellText(DomainValidation.requireText(graphicsDriver, "graphicsDriver"), "graphicsDriver");
        this.dxWrapper = requireShellText(DomainValidation.requireText(dxWrapper, "dxWrapper"), "dxWrapper");
        this.audioDriver = requireShellText(DomainValidation.requireText(audioDriver, "audioDriver"), "audioDriver");
        this.resolution = requireShellText(DomainValidation.requireText(resolution, "resolution"), "resolution");
        this.box64Preset = requireShellText(DomainValidation.requireText(box64Preset, "box64Preset"), "box64Preset");
        this.inputProfileId = DomainValidation.requireText(inputProfileId, "inputProfileId");
        if (!this.inputProfileId.matches("(?:xinput|dinput)(?::[1-9][0-9]{0,5})?")) {
            throw new IllegalArgumentException("invalid inputProfileId");
        }
        if (launchExecutionMode == null) {
            throw new IllegalArgumentException("launchExecutionMode must not be null");
        }
        this.launchExecutionMode = launchExecutionMode;
        this.environment = DomainValidation.immutableTextMap(environment, "environment");
        if (this.environment.size() > MAX_ENVIRONMENT) {
            throw new IllegalArgumentException("too many launch environment entries");
        }
        for (Map.Entry<String, String> entry : this.environment.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid environment key");
            }
            requireShellText(entry.getValue(), "environment value");
        }
        this.eventPath = requireAbsolute(eventPath, "eventPath");
        this.logPath = requireAbsolute(logPath, "logPath");
        this.lockPath = requireAbsolute(lockPath, "lockPath");
        this.cancelPath = requireAbsolute(cancelPath, "cancelPath");
        if (timeoutSeconds < 30 || timeoutSeconds > 86400) {
            throw new IllegalArgumentException("invalid timeoutSeconds");
        }
        this.timeoutSeconds = timeoutSeconds;
        if (runtimeBackendType == null) {
            throw new IllegalArgumentException("runtimeBackendType required");
        }
        this.runtimeBackendType = runtimeBackendType;
        this.rootfsPackage = DomainValidation.optionalText(rootfsPackage);
        if (!this.rootfsPackage.isEmpty() &&
            !this.rootfsPackage.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid rootfsPackage");
        }
        this.runtimeRootPath = DomainValidation.optionalText(runtimeRootPath);
        if (runtimeBackendType == GameRuntimeBackendType.ROOTFS_PROOT) {
            if (this.rootfsPackage.isEmpty()) {
                throw new IllegalArgumentException("rootfsPackage required");
            }
            requireAbsolute(this.runtimeRootPath, "runtimeRootPath");
        } else if (!this.rootfsPackage.isEmpty() || !this.runtimeRootPath.isEmpty()) {
            throw new IllegalArgumentException("rootfs fields not supported for GLIBC backend");
        }
    }

    private static String requireId(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value;
    }

    private static String requireAbsolute(String value, String field) {
        String result = requireShellText(DomainValidation.requireText(value, field), field);
        if (!new File(result).isAbsolute() || result.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(field + " must be absolute");
        }
        return result;
    }

    private static String requireRelative(String value, boolean allowRoot, String field) {
        if (value == null || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0 ||
            value.startsWith("/") || value.contains(":")) {
            throw new IllegalArgumentException("invalid " + field);
        }
        requireShellText(value, field);
        if (allowRoot && ".".equals(value)) return value;
        if (value.isEmpty()) throw new IllegalArgumentException("invalid " + field);
        String[] segments = value.split("/", -1);
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("invalid " + field);
            }
        }
        return value;
    }

    private static String requireShellText(String value, String field) {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line break not supported in " + field);
        }
        return value;
    }

    public String getTaskId() { return taskId; }
    public String getGameId() { return gameId; }
    public String getGameRootPath() { return gameRootPath; }
    public String getExecutable() { return executable; }
    public String getWorkingDirectory() { return workingDirectory; }
    public List<String> getArguments() { return arguments; }
    public String getPrefixPath() { return prefixPath; }
    public String getWinePackage() { return winePackage; }
    public String getGraphicsDriver() { return graphicsDriver; }
    public String getDxWrapper() { return dxWrapper; }
    public String getAudioDriver() { return audioDriver; }
    public String getResolution() { return resolution; }
    public String getBox64Preset() { return box64Preset; }
    public String getInputProfileId() { return inputProfileId; }
    public LaunchExecutionMode getLaunchExecutionMode() { return launchExecutionMode; }
    public Map<String, String> getEnvironment() { return environment; }
    public String getEventPath() { return eventPath; }
    public String getLogPath() { return logPath; }
    public String getLockPath() { return lockPath; }
    public String getCancelPath() { return cancelPath; }
    public long getTimeoutSeconds() { return timeoutSeconds; }
    public GameRuntimeBackendType getRuntimeBackendType() { return runtimeBackendType; }
    public String getRootfsPackage() { return rootfsPackage; }
    public String getRuntimeRootPath() { return runtimeRootPath; }
}
