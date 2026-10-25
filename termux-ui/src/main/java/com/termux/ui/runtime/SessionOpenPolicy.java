package com.termux.ui.runtime;

import com.termux.ui.domain.LaunchStage;
import com.termux.ui.domain.LaunchTaskState;
import com.termux.ui.domain.XServerBackendType;

/**
 * Decides when the launch screen hands over to the display session.
 *
 * <p>The in-process X server creates its own display, so the session view may appear once the
 * first frame is awaited. The independent Xlorie process is a render-bridge <em>client</em>: it
 * only connects while the app's session view is bound and listening, so the view must open before
 * the script starts the X server.
 */
public final class SessionOpenPolicy {

    private SessionOpenPolicy() {}

    public static boolean shouldOpen(XServerBackendType backend, LaunchStage stage, LaunchTaskState state) {
        if (stage == null || state == null || state.isTerminal()) return false;
        if (backend == XServerBackendType.XLORIE_EXTERNAL) {
            return stage.ordinal() >= LaunchStage.PREPARING_PREFIX.ordinal()
                && stage.ordinal() <= LaunchStage.RUNNING.ordinal();
        }
        return stage == LaunchStage.WAITING_FIRST_FRAME || stage == LaunchStage.RUNNING;
    }
}
