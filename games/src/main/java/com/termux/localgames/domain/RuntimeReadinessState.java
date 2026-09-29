package com.termux.localgames.domain;

/** How far along one independent runtime environment (GLIBC or RootFS) is toward launch-ready. */
public enum RuntimeReadinessState {
    /** Nothing has been installed yet. */
    NOT_READY,
    /** Some install progress exists on disk but verification did not fully pass. */
    INCOMPLETE,
    /** All install-verification markers are present. */
    READY
}
