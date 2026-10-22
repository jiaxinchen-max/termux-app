package com.termux.x11.controller.inputcontrols;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Screen orientation a controller profile is designed for. A profile file stores it as the
 * {@code orientation} field; files without the field (every profile made before the field existed)
 * are landscape profiles.
 */
public enum ControlsOrientation {
    LANDSCAPE("landscape"),
    PORTRAIT("portrait");

    public static final ControlsOrientation DEFAULT = LANDSCAPE;

    private final String storageValue;

    ControlsOrientation(String storageValue) {
        this.storageValue = storageValue;
    }

    /** Value of the profile file's {@code orientation} field. */
    @NonNull
    public String storageValue() {
        return storageValue;
    }

    /** Parses a stored value; a missing or unknown value is {@link #DEFAULT}. */
    @NonNull
    public static ControlsOrientation fromStorage(@Nullable String value) {
        for (ControlsOrientation orientation : values()) {
            if (orientation.storageValue.equals(value)) return orientation;
        }
        return DEFAULT;
    }

    /** Orientation of a surface of the given size; a square surface counts as landscape. */
    @NonNull
    public static ControlsOrientation ofSize(int width, int height) {
        return height > width ? PORTRAIT : LANDSCAPE;
    }

    @NonNull
    public ControlsOrientation opposite() {
        return this == LANDSCAPE ? PORTRAIT : LANDSCAPE;
    }
}
