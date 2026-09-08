package com.termux.localgames.domain;

public enum RuntimeProvisionTaskState {
    QUEUED,
    PREPARING,
    BUILDING,
    VERIFYING,
    ACTIVATING,
    SUCCEEDED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
