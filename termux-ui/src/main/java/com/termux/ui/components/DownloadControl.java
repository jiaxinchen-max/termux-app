package com.termux.ui.components;

public interface DownloadControl {

    enum Decision {
        CONTINUE,
        PAUSE,
        CANCEL
    }

    DownloadControl CONTINUE = () -> Decision.CONTINUE;

    Decision currentDecision();
}
