package com.termux.ui.wine.domain;

public enum RuntimeSetupTaskState {
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
