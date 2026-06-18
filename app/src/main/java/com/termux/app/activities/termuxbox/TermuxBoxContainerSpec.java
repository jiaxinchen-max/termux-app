package com.termux.app.activities.termuxbox;

import androidx.annotation.NonNull;

public final class TermuxBoxContainerSpec {
    public final String id;
    public final String name;
    public final String winePackage;
    public final String resolution;
    public final String locale;
    public final String gpuDriver;
    public final String gpuAccel;
    public final String audioDriver;

    public TermuxBoxContainerSpec(@NonNull String id, @NonNull String name, @NonNull String winePackage,
                                  @NonNull String resolution, @NonNull String locale,
                                  @NonNull String gpuDriver, @NonNull String gpuAccel,
                                  @NonNull String audioDriver) {
        this.id = id;
        this.name = name;
        this.winePackage = winePackage;
        this.resolution = resolution;
        this.locale = locale;
        this.gpuDriver = gpuDriver;
        this.gpuAccel = gpuAccel;
        this.audioDriver = audioDriver;
    }
}
