package com.termux.ui.platform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.pm.ActivityInfo;

import org.junit.Test;

public final class OrientationPreferenceTest {

    @Test
    public void mapsEachChoiceToItsRequestedOrientation() {
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            OrientationPreference.VERTICAL.getRequestedOrientation());
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            OrientationPreference.HORIZONTAL.getRequestedOrientation());
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
            OrientationPreference.AUTO.getRequestedOrientation());
    }

    @Test
    public void storageValuesRoundTrip() {
        for (OrientationPreference choice : OrientationPreference.values()) {
            assertEquals(choice, OrientationPreference.fromStorageValue(choice.getStorageValue()));
        }
    }

    @Test
    public void storageValuesAreDistinct() {
        assertNotEquals(OrientationPreference.VERTICAL.getStorageValue(),
            OrientationPreference.HORIZONTAL.getStorageValue());
        assertNotEquals(OrientationPreference.HORIZONTAL.getStorageValue(),
            OrientationPreference.AUTO.getStorageValue());
    }

    @Test
    public void unknownOrMissingValueFallsBackToAuto() {
        assertEquals(OrientationPreference.AUTO, OrientationPreference.fromStorageValue(null));
        assertEquals(OrientationPreference.AUTO, OrientationPreference.fromStorageValue(""));
        assertEquals(OrientationPreference.AUTO, OrientationPreference.fromStorageValue("sideways"));
    }
}
