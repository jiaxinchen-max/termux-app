package com.termux.ui.activity;

/** Only two consecutive root-page Back presses inside the confirmation window exit the app. */
final class BackExitConfirmation {
    static final long WINDOW_MS = 2000;
    private long firstBackAt = -1;

    boolean onBack(long now) {
        if (firstBackAt >= 0 && now >= firstBackAt && now - firstBackAt < WINDOW_MS) {
            reset();
            return true;
        }
        firstBackAt = now;
        return false;
    }

    void reset() {
        firstBackAt = -1;
    }
}
