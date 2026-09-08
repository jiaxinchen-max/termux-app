package com.termux.localgames.components.install;

import com.termux.localgames.components.DownloadControl;

final class InstallInterruptedException extends ComponentInstallException {
    private final DownloadControl.Decision decision;

    InstallInterruptedException(DownloadControl.Decision decision) {
        super(decision == DownloadControl.Decision.PAUSE ? "install_paused" : "install_cancelled",
            decision == DownloadControl.Decision.PAUSE
                ? "Component installation paused" : "Component installation cancelled");
        this.decision = decision;
    }

    DownloadControl.Decision getDecision() { return decision; }
}
