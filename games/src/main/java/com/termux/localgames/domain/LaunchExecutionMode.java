package com.termux.localgames.domain;

/** Existing Termux runner selected for one local-game launch. */
public enum LaunchExecutionMode {
    APP_SHELL("app_shell"),
    TERMINAL_SESSION("terminal_session");

    private final String storageValue;

    LaunchExecutionMode(String storageValue) {
        this.storageValue = storageValue;
    }

    public String getStorageValue() { return storageValue; }

    public static LaunchExecutionMode fromStorageValue(String value) {
        for (LaunchExecutionMode mode : values()) {
            if (mode.storageValue.equals(value)) return mode;
        }
        throw new IllegalArgumentException("invalid launchExecutionMode");
    }
}
