package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.DesktopSessionState;

/** Pure derivation of a distribution's state from observable facts; no I/O. */
public final class DesktopSessionStateResolver {

    /** Values the scripts write to the status file. */
    public static final String STATUS_STARTING = "starting";
    public static final String STATUS_STOPPING = "stopping";
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
        // The launcher can exit before the stop supervisor publishes its final result.
        // Neither an old ready marker nor a missing PID changes this requested transition.
        if (STATUS_STOPPING.equals(status)) return DesktopSessionState.STOPPING;
        if (isFailureStatus(status)) return DesktopSessionState.FAILED;
        // Status is read after the PID facts: a terminal result can supersede
        // an earlier alive/ready observation from the same refresh.
        if (STATUS_STOPPED.equals(status) || "exit:0".equals(status)) return DesktopSessionState.INSTALLED;
        if (processAlive) {
            return socketPresent && readyMarker ? DesktopSessionState.RUNNING
                : DesktopSessionState.STARTING;
        }
        if (status == null || status.isEmpty()) {
            return DesktopSessionState.INSTALLED;
        }
        // A start that never reached "stopped": it exited by itself.
        return DesktopSessionState.FAILED;
    }

    public static boolean isFailureStatus(String status) {
        return status != null && status.startsWith("exit:") && !"exit:0".equals(status);
    }
}
