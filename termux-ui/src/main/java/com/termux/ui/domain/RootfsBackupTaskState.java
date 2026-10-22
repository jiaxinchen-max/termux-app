package com.termux.ui.domain;

public enum RootfsBackupTaskState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
