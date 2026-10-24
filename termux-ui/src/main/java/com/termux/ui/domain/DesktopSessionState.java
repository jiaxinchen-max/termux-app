package com.termux.ui.domain;

/** Derived lifecycle state of one desktop distribution; never persisted. */
public enum DesktopSessionState {
    NOT_INSTALLED,
    INSTALLED,
    STARTING,
    RUNNING,
    FAILED
}
