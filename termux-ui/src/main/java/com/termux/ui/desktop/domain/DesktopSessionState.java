package com.termux.ui.desktop.domain;

/** Derived lifecycle state of one desktop distribution; never persisted. */
public enum DesktopSessionState {
    NOT_INSTALLED,
    INSTALLED,
    STARTING,
    RUNNING,
    STOPPING,
    FAILED;

    /** An active desktop still owns its files/runtime, including while cleanup is pending. */
    public boolean isActive() {
        return this == STARTING || this == RUNNING || this == STOPPING;
    }
}
