package com.termux.ui.components.install;

import com.termux.ui.components.DownloadControl;

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
