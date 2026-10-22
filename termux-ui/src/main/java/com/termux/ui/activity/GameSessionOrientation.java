package com.termux.ui.activity;

import android.content.pm.ActivityInfo;

import androidx.annotation.NonNull;

import com.termux.ui.data.OrientationPreference;

/** Pure mapping from the Settings "Orientation" choice to the game session's requested orientation. */
final class GameSessionOrientation {

    private GameSessionOrientation() {}

    /**
     * HORIZONTAL keeps the historical landscape session, VERTICAL runs it in portrait, and AUTO
     * follows the user's system rotation setting in either direction. The library screen keeps its
     * own mapping in {@link OrientationPreference#getRequestedOrientation()}.
     */
    static int requestedFor(@NonNull OrientationPreference preference) {
        switch (preference) {
            case HORIZONTAL:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE;
            case VERTICAL:
                return ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT;
            case AUTO:
            default:
                return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        }
    }
}
