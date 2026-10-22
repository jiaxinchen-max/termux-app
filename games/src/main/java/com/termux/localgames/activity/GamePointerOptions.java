package com.termux.localgames.activity;

import androidx.annotation.NonNull;

import com.termux.x11.X11SessionView.PointerMode;

/**
 * The Lock cursor and Touch screen mode switches of the Virtual controller page and the pointer
 * mode they stand for. The two switches exclude each other: locking the cursor wins and shows the
 * touch screen switch as off and disabled; with neither on the pointer works like a touchpad.
 */
final class GamePointerOptions {

    private GamePointerOptions() { }

    @NonNull
    static PointerMode modeFor(boolean lockCursor, boolean touchScreen) {
        if (lockCursor) return PointerMode.LOCKED_CURSOR;
        return touchScreen ? PointerMode.TOUCH_SCREEN : PointerMode.TOUCHPAD;
    }

    static boolean isLockCursor(@NonNull PointerMode mode) {
        return mode == PointerMode.LOCKED_CURSOR;
    }

    static boolean isTouchScreen(@NonNull PointerMode mode) {
        return mode == PointerMode.TOUCH_SCREEN;
    }

    /** The Touch screen switch can only be used while the cursor is not locked. */
    static boolean isTouchScreenSwitchEnabled(@NonNull PointerMode mode) {
        return mode != PointerMode.LOCKED_CURSOR;
    }
}
