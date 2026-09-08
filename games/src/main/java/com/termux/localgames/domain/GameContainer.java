package com.termux.localgames.domain;

import java.util.Map;

/**
 * A complete isolated PC runtime. A container owns its Wine prefix (C drive), runtime
 * selections and CPU translator; any game may reference exactly one container id.
 */
public final class GameContainer {

    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_ID = "default";
    /** Runtime activation ID; this is distinct from the component that supplies its recipe. */
    public static final String ROOTFS_RUNTIME_PACKAGE = "debian-13-games-rootfs";
    private static final String ROOTFS_RECIPE_SOURCE = "hangover-11.9-debian13-source";

    private final String id;
    private final String name;
    private final GameRuntimeBackendType backendType;
    private final String rootfsPackage;
    private final RuntimeTranslator translator;
    private final String winePackage;
    private final String graphicsDriver;
    private final String dxWrapper;
    private final String audioDriver;
    private final String resolution;
    private final String box64Preset;
    private final Map<String, String> environment;

    public GameContainer(String id, String name, GameRuntimeBackendType backendType,
                         String rootfsPackage, RuntimeTranslator translator,
                         String winePackage, String graphicsDriver, String dxWrapper,
                         String audioDriver, String resolution, String box64Preset,
                         Map<String, String> environment) {
        this.id = requireId(id);
        this.name = DomainValidation.requireText(name, "containerName");
        if (backendType == null) throw new IllegalArgumentException("containerBackendRequired");
        if (translator == null) throw new IllegalArgumentException("containerTranslatorRequired");
        this.backendType = backendType;
        String requestedRootfsPackage = DomainValidation.optionalText(rootfsPackage);
        // Earlier container UI code persisted the recipe source component here.  The launcher
        // must resolve the activated runtime package instead, otherwise every PRoot launch is
        // rejected before its script can run.
        this.rootfsPackage = ROOTFS_RECIPE_SOURCE.equals(requestedRootfsPackage)
            ? ROOTFS_RUNTIME_PACKAGE : requestedRootfsPackage;
        if (!this.rootfsPackage.isEmpty() && !this.rootfsPackage.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalidContainerRootfs");
        }
        if (backendType == GameRuntimeBackendType.ROOTFS_PROOT && this.rootfsPackage.isEmpty()) {
            throw new IllegalArgumentException("containerRootfsRequired");
        }
        if (backendType == GameRuntimeBackendType.GLIBC_TERMUX_BOX && !this.rootfsPackage.isEmpty()) {
            throw new IllegalArgumentException("containerRootfsUnsupported");
        }
        this.translator = translator;
        this.winePackage = DomainValidation.requireText(winePackage, "containerWinePackage");
        this.graphicsDriver = DomainValidation.requireText(graphicsDriver, "containerGraphicsDriver");
        this.dxWrapper = DomainValidation.requireText(dxWrapper, "containerDxWrapper");
        this.audioDriver = DomainValidation.requireText(audioDriver, "containerAudioDriver");
        this.resolution = DomainValidation.requireText(resolution, "containerResolution");
        this.box64Preset = DomainValidation.requireText(box64Preset, "containerBox64Preset");
        this.environment = DomainValidation.immutableTextMap(environment, "containerEnvironment");
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public GameRuntimeBackendType getBackendType() { return backendType; }
    public String getRootfsPackage() { return rootfsPackage; }
    public RuntimeTranslator getTranslator() { return translator; }
    public String getWinePackage() { return winePackage; }
    public String getGraphicsDriver() { return graphicsDriver; }
    public String getDxWrapper() { return dxWrapper; }
    public String getAudioDriver() { return audioDriver; }
    public String getResolution() { return resolution; }
    public String getBox64Preset() { return box64Preset; }
    public Map<String, String> getEnvironment() { return environment; }

    private static String requireId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalidContainerId");
        }
        return value;
    }
}
