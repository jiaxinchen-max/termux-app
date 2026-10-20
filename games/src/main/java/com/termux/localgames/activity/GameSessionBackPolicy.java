package com.termux.localgames.activity;

/**
 * Pure BACK decision for the game session, shared by every orientation and every BACK route.
 *
 * <p>BACK drives the control drawer only: it opens a closed drawer, steps back out of a drawer
 * sub-level, and closes an open drawer at its top level. It never asks to exit the game; leaving
 * is the Exit button's job.
 */
final class GameSessionBackPolicy {

    enum Action {
        /** The drawer is closed: slide it in. */
        OPEN_DRAWER,
        /** The drawer is open inside a sub-level: go back to the menu one level up. */
        NAVIGATE_UP,
        /** The drawer is open at its top level: slide it out. */
        CLOSE_DRAWER,
        /** A dialog is showing or about to show (it handles BACK itself), or the game is exiting. */
        IGNORE
    }

    /** One physical BACK can arrive through several routes; repeats this close together are one. */
    static final long DUPLICATE_WINDOW_MS = 250L;

    private GameSessionBackPolicy() {}

    static Action decide(boolean drawerOpen, boolean drawerHasSubLevel,
                         boolean dialogShowingOrPending) {
        if (dialogShowingOrPending) return Action.IGNORE;
        if (!drawerOpen) return Action.OPEN_DRAWER;
        return drawerHasSubLevel ? Action.NAVIGATE_UP : Action.CLOSE_DRAWER;
    }

    /** True when {@code nowMs} is a repeat of the BACK handled at {@code lastHandledMs}. */
    static boolean isDuplicate(long lastHandledMs, long nowMs) {
        return lastHandledMs >= 0 && nowMs >= lastHandledMs && nowMs - lastHandledMs < DUPLICATE_WINDOW_MS;
    }
}
