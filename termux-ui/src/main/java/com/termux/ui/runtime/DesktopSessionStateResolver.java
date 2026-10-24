package com.termux.ui.runtime;

import com.termux.ui.domain.DesktopSessionState;

/** Pure derivation of a distribution's state from observable facts; no I/O. */
public final class DesktopSessionStateResolver {

    /** Values the scripts write to the status file. */
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_STOPPED = "stopped";

    private DesktopSessionStateResolver() {}

    /**
     * @param installed     the required packages are present for the distribution's run mode
     * @param processAlive  the pid recorded by the start script is alive
     * @param socketPresent KWin's Wayland socket exists
     * @param readyMarker   the log already contains {@link DesktopLaunchSpec#READY_LOG_MARKER}
     * @param status        the content of the status file, trimmed; may be null or empty
     */
    public static DesktopSessionState resolve(boolean installed, boolean processAlive,
                                              boolean socketPresent, boolean readyMarker,
                                              String status) {
        if (!installed) return DesktopSessionState.NOT_INSTALLED;
        if (processAlive) {
            return socketPresent && readyMarker ? DesktopSessionState.RUNNING
                : DesktopSessionState.STARTING;
        }
        if (status == null || status.isEmpty() || STATUS_STOPPED.equals(status)) {
            return DesktopSessionState.INSTALLED;
        }
        // A start that never reached "stopped": it exited by itself.
        return status.startsWith("exit:0") ? DesktopSessionState.INSTALLED
            : DesktopSessionState.FAILED;
    }
}
