package com.termux.localgames.domain;

public enum ComponentTaskState {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    VERIFIED,
    INSTALLING,
    INSTALLED,
    FAILED,
    CANCELLED;

    public boolean isTerminal() {
        return this == INSTALLED || this == CANCELLED;
    }

    public boolean shouldRecoverAutomatically() {
        return this == QUEUED || this == DOWNLOADING || this == VERIFYING ||
            this == VERIFIED || this == INSTALLING;
    }
}
