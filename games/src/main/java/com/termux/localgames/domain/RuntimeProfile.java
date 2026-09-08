package com.termux.localgames.domain;

import java.util.Map;
import java.util.regex.Pattern;

/** Immutable game-level runtime selection frozen before launch. */
public final class RuntimeProfile {

    public static final int SCHEMA_VERSION = 4;
    private static final Pattern ENVIRONMENT_KEY =
        Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern COMPONENT_ID = Pattern.compile("[A-Za-z0-9._-]+");

    private final String id;
    private final String winePackage;
    private final String graphicsDriver;
    private final String dxWrapper;
    private final String audioDriver;
    private final String resolution;
    private final String box64Preset;
    private final Map<String, String> environment;
    private final String inputProfileId;
    private final LaunchExecutionMode launchExecutionMode;
    private final Map<String, String> componentVersions;
    private final GameRuntimeBackendType runtimeBackendType;
    private final String rootfsPackage;
    private final String containerId;

    public RuntimeProfile(String id, String winePackage, String graphicsDriver,
                          String dxWrapper, String audioDriver, String resolution,
                          String box64Preset, Map<String, String> environment,
                          String inputProfileId, Map<String, String> componentVersions) {
        this(id, winePackage, graphicsDriver, dxWrapper, audioDriver, resolution,
            box64Preset, environment, inputProfileId, LaunchExecutionMode.APP_SHELL,
            componentVersions, GameRuntimeBackendType.GLIBC_TERMUX_BOX, "", GameContainer.DEFAULT_ID);
    }

    public RuntimeProfile(String id, String winePackage, String graphicsDriver,
                          String dxWrapper, String audioDriver, String resolution,
                          String box64Preset, Map<String, String> environment,
                          String inputProfileId, LaunchExecutionMode launchExecutionMode,
                          Map<String, String> componentVersions) {
        this(id, winePackage, graphicsDriver, dxWrapper, audioDriver, resolution,
            box64Preset, environment, inputProfileId, launchExecutionMode,
            componentVersions, GameRuntimeBackendType.GLIBC_TERMUX_BOX, "", GameContainer.DEFAULT_ID);
    }

    public RuntimeProfile(String id, String winePackage, String graphicsDriver,
                          String dxWrapper, String audioDriver, String resolution,
                          String box64Preset, Map<String, String> environment,
                          String inputProfileId, LaunchExecutionMode launchExecutionMode,
                          Map<String, String> componentVersions,
                          GameRuntimeBackendType runtimeBackendType,
                          String rootfsPackage) {
        this(id, winePackage, graphicsDriver, dxWrapper, audioDriver, resolution, box64Preset,
            environment, inputProfileId, launchExecutionMode, componentVersions,
            runtimeBackendType, rootfsPackage, GameContainer.DEFAULT_ID);
    }

    public RuntimeProfile(String id, String winePackage, String graphicsDriver,
                          String dxWrapper, String audioDriver, String resolution,
                          String box64Preset, Map<String, String> environment,
                          String inputProfileId, LaunchExecutionMode launchExecutionMode,
                          Map<String, String> componentVersions,
                          GameRuntimeBackendType runtimeBackendType,
                          String rootfsPackage, String containerId) {
        this.id = DomainValidation.requireText(id, "id");
        this.winePackage = DomainValidation.requireText(winePackage, "winePackage");
        this.graphicsDriver = DomainValidation.requireText(graphicsDriver, "graphicsDriver");
        this.dxWrapper = DomainValidation.requireText(dxWrapper, "dxWrapper");
        this.audioDriver = DomainValidation.requireText(audioDriver, "audioDriver");
        this.resolution = DomainValidation.requireText(resolution, "resolution");
        this.box64Preset = DomainValidation.requireText(box64Preset, "box64Preset");
        this.environment = DomainValidation.immutableTextMap(environment, "environment");
        this.inputProfileId = DomainValidation.optionalText(inputProfileId);
        if (launchExecutionMode == null) {
            throw new IllegalArgumentException("launchExecutionMode must not be null");
        }
        this.launchExecutionMode = launchExecutionMode;
        this.componentVersions = DomainValidation.immutableTextMap(
            componentVersions, "componentVersions");
        if (runtimeBackendType == null) {
            throw new IllegalArgumentException("runtimeBackendType must not be null");
        }
        this.runtimeBackendType = runtimeBackendType;
        this.rootfsPackage = DomainValidation.optionalText(rootfsPackage);
        this.containerId = requireContainerId(containerId);
        if (!this.rootfsPackage.isEmpty() && !COMPONENT_ID.matcher(this.rootfsPackage).matches()) {
            throw new IllegalArgumentException("invalid rootfsPackage");
        }
        if (runtimeBackendType == GameRuntimeBackendType.ROOTFS_PROOT &&
            this.rootfsPackage.isEmpty()) {
            throw new IllegalArgumentException("rootfsPackage required for ROOTFS_PROOT");
        }
        if (runtimeBackendType == GameRuntimeBackendType.GLIBC_TERMUX_BOX &&
            !this.rootfsPackage.isEmpty()) {
            throw new IllegalArgumentException("rootfsPackage not supported for GLIBC_TERMUX_BOX");
        }
        validateMaps();
    }

    public String getId() { return id; }
    public String getWinePackage() { return winePackage; }
    public String getGraphicsDriver() { return graphicsDriver; }
    public String getDxWrapper() { return dxWrapper; }
    public String getAudioDriver() { return audioDriver; }
    public String getResolution() { return resolution; }
    public String getBox64Preset() { return box64Preset; }
    public Map<String, String> getEnvironment() { return environment; }
    public String getInputProfileId() { return inputProfileId; }
    public LaunchExecutionMode getLaunchExecutionMode() { return launchExecutionMode; }
    public Map<String, String> getComponentVersions() { return componentVersions; }
    public GameRuntimeBackendType getRuntimeBackendType() { return runtimeBackendType; }
    public String getRootfsPackage() { return rootfsPackage; }
    public String getContainerId() { return containerId; }

    public RuntimeProfile withLaunchExecutionMode(LaunchExecutionMode mode) {
        return new RuntimeProfile(id, winePackage, graphicsDriver, dxWrapper, audioDriver,
            resolution, box64Preset, environment, inputProfileId, mode, componentVersions,
            runtimeBackendType, rootfsPackage, containerId);
    }

    public RuntimeProfile withRuntimeBackend(GameRuntimeBackendType type, String rootfs) {
        return new RuntimeProfile(id, winePackage, graphicsDriver, dxWrapper, audioDriver,
            resolution, box64Preset, environment, inputProfileId, launchExecutionMode,
            componentVersions, type, rootfs, containerId);
    }

    public RuntimeProfile withContainerId(String value) {
        return new RuntimeProfile(id, winePackage, graphicsDriver, dxWrapper, audioDriver,
            resolution, box64Preset, environment, inputProfileId, launchExecutionMode,
            componentVersions, runtimeBackendType, rootfsPackage, value);
    }

    private static String requireContainerId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalidContainerId");
        }
        return value;
    }

    private void validateMaps() {
        if (environment.size() > 256 || componentVersions.size() > 256) {
            throw new IllegalArgumentException("runtime profile map is too large");
        }
        for (String key : environment.keySet()) {
            if (!ENVIRONMENT_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("invalid environment key: " + key);
            }
        }
        for (Map.Entry<String, String> entry : componentVersions.entrySet()) {
            if (!COMPONENT_ID.matcher(entry.getKey()).matches()) {
                throw new IllegalArgumentException("invalid component id: " + entry.getKey());
            }
            try {
                if (Integer.parseInt(entry.getValue()) < 1) {
                    throw new IllegalArgumentException("invalid component version: " + entry.getKey());
                }
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException("invalid component version: " + entry.getKey(), error);
            }
        }
    }
}
