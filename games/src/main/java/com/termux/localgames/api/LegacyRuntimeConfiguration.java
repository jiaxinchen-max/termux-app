package com.termux.localgames.api;

import androidx.annotation.NonNull;

/** Immutable, read-only snapshot of an app-owned legacy runtime configuration. */
public final class LegacyRuntimeConfiguration {
    private final String id;
    private final String name;
    private final String winePackage;
    private final String graphicsDriver;
    private final String dxWrapper;
    private final String audioDriver;
    private final String resolution;
    private final String box64Preset;
    private final String environment;
    private final String inputProfileId;

    public LegacyRuntimeConfiguration(@NonNull String id, @NonNull String name,
                                      @NonNull String winePackage,
                                      @NonNull String graphicsDriver,
                                      @NonNull String dxWrapper,
                                      @NonNull String audioDriver,
                                      @NonNull String resolution,
                                      @NonNull String box64Preset,
                                      @NonNull String environment,
                                      @NonNull String inputProfileId) {
        this.id = require(id, "id");
        this.name = require(name, "name");
        this.winePackage = require(winePackage, "winePackage");
        this.graphicsDriver = require(graphicsDriver, "graphicsDriver");
        this.dxWrapper = require(dxWrapper, "dxWrapper");
        this.audioDriver = require(audioDriver, "audioDriver");
        this.resolution = require(resolution, "resolution");
        this.box64Preset = require(box64Preset, "box64Preset");
        this.environment = nonNull(environment, "environment");
        this.inputProfileId = nonNull(inputProfileId, "inputProfileId");
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getWinePackage() { return winePackage; }
    public String getGraphicsDriver() { return graphicsDriver; }
    public String getDxWrapper() { return dxWrapper; }
    public String getAudioDriver() { return audioDriver; }
    public String getResolution() { return resolution; }
    public String getBox64Preset() { return box64Preset; }
    public String getEnvironment() { return environment; }
    public String getInputProfileId() { return inputProfileId; }

    private static String require(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String nonNull(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " must not be null");
        return value;
    }
}
