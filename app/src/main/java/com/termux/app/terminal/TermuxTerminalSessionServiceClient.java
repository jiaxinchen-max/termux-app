package com.termux.app.terminal;

import android.app.Service;

import androidx.annotation.NonNull;

import com.termux.app.TermuxService;
import com.termux.app.localgames.TermuxGamesProvisionTerminalRegistry;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class TermuxTerminalSessionServiceClient extends TermuxTerminalSessionClientBase {

    private static final String LOG_TAG = "TermuxTerminalSessionServiceClient";

    private final TermuxService mService;

    public TermuxTerminalSessionServiceClient(TermuxService service) {
        this.mService = service;
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }

    @Override
    public void onSessionFinished(@NonNull TerminalSession finishedSession) {
        // There is no Activity client when Games is foregrounded. Remove its completed
        // provisioning session here, equivalent to the Enter-key path in TermuxActivity.
        if (TermuxGamesProvisionTerminalRegistry.isProvisionSession(finishedSession.mSessionName)) {
            mService.removeTermuxSession(finishedSession);
        }
    }

}
