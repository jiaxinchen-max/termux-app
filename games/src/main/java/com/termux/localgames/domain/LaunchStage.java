package com.termux.localgames.domain;

public enum LaunchStage {
    QUEUED,
    PRECHECK,
    PREPARING_COMPONENTS,
    PREPARING_PREFIX,
    STARTING_DISPLAY,
    STARTING_AUDIO,
    STARTING_GAME,
    WAITING_FIRST_FRAME,
    RUNNING,
    STOPPING,
    CLEANING,
    COMPLETE
}
