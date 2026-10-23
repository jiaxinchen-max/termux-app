package com.termux.x11.controller.winhandler;

/**
 * Tracks whether the Wine end of the bridge (winhandler.exe and the patched xinput/dinput DLLs) is up.
 *
 * winhandler.exe sends INIT exactly once, right after it starts. When that datagram arrives before the Android side
 * has bound its port it is lost, and every reply stays queued for ever. Any gamepad request proves that Wine is up as
 * well, so it counts as an implicit INIT.
 */
final class WinHandlerHandshake {
    private volatile boolean ready;

    boolean isReady() {
        return ready;
    }

    /** Returns true when this request is what made the handler ready. */
    boolean onRequest(byte requestCode) {
        if (ready || !isReadyEvidence(requestCode)) return false;
        ready = true;
        return true;
    }

    /** Whether a request proves that the Wine end is listening, so queued replies can be sent. */
    static boolean isReadyEvidence(byte requestCode) {
        switch (requestCode) {
            case RequestCodes.INIT:
            case RequestCodes.GET_GAMEPAD:
            case RequestCodes.GET_GAMEPAD_STATE:
                return true;
            default:
                return false;
        }
    }
}
