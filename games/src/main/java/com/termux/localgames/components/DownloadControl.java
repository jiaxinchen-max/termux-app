package com.termux.localgames.components;

public interface DownloadControl {

    enum Decision {
        CONTINUE,
        PAUSE,
        CANCEL
    }

    DownloadControl CONTINUE = () -> Decision.CONTINUE;

    Decision currentDecision();
}
