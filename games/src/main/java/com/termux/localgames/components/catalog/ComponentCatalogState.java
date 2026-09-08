package com.termux.localgames.components.catalog;

public enum ComponentCatalogState {
    NOT_INSTALLED,
    QUEUED,
    DOWNLOADING,
    PAUSED,
    VERIFYING,
    VERIFIED,
    INSTALLING,
    INSTALLED,
    UPDATE_AVAILABLE,
    FAILED
}
