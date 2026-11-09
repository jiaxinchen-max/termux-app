package com.termux.ui.wine.runtime;

import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;

/** Hangover's built-in i386 emulator; x86_64 continues to use ARM64EC FEX. */
public enum HangoverEmulationMode {
    WOWBOX64("wowbox64"),
    FEX("fex");

    public static final String ENVIRONMENT_KEY = "GAMES_HANGOVER_EMULATOR";
    private final String value;

    HangoverEmulationMode(String value) { this.value = value; }

    public String getStorageValue() { return value; }

    public static boolean appliesTo(RuntimeProfile profile) {
        return profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT
            && profile.getWinePackage().startsWith("rootfs_hangover_");
    }

    public static HangoverEmulationMode selected(RuntimeProfile profile) {
        String value = profile.getEnvironment().getOrDefault(ENVIRONMENT_KEY, "");
        if (value.isEmpty()) return WOWBOX64;
        for (HangoverEmulationMode mode : values()) {
            if (mode.value.equals(value)) return mode;
        }
        throw new IllegalArgumentException("hangover_emulator_invalid");
    }
}
