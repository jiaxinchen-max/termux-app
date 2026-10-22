package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;

import android.content.pm.ActivityInfo;

import com.termux.ui.platform.OrientationPreference;

import org.junit.Test;

public class GameSessionOrientationTest {

    @Test
    public void horizontalKeepsTheSensorLandscapeSession() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
            GameSessionOrientation.requestedFor(OrientationPreference.HORIZONTAL));
    }

    @Test
    public void verticalRunsTheSessionInSensorPortrait() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
            GameSessionOrientation.requestedFor(OrientationPreference.VERTICAL));
    }

    @Test
    public void autoFollowsTheUsersRotationSetting() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER,
            GameSessionOrientation.requestedFor(OrientationPreference.AUTO));
    }

    @Test
    public void everyPreferenceHasADistinctSessionRequest() {
        java.util.Set<Integer> requests = new java.util.HashSet<>();
        for (OrientationPreference preference : OrientationPreference.values())
            requests.add(GameSessionOrientation.requestedFor(preference));
        assertEquals(OrientationPreference.values().length, requests.size());
    }
}
