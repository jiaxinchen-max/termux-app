package com.termux.ui.activity;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SettingsMenuBuilderTest {

    @Test
    public void headingIsDroppedWhenTheVisibleToolbarShowsTheSameTitle() {
        assertTrue(SettingsMenuBuilder.repeatsToolbarTitle(true, "Settings", "Settings"));
        assertTrue(SettingsMenuBuilder.repeatsToolbarTitle(true, " Settings ", "Settings"));
    }

    @Test
    public void headingIsKeptWhenTheToolbarIsHiddenOrShowsOtherText() {
        assertFalse(SettingsMenuBuilder.repeatsToolbarTitle(false, "Settings", "Settings"));
        assertFalse(SettingsMenuBuilder.repeatsToolbarTitle(true, "Games", "Settings"));
        assertFalse(SettingsMenuBuilder.repeatsToolbarTitle(true, "", ""));
        assertFalse(SettingsMenuBuilder.repeatsToolbarTitle(true, null, "Settings"));
        assertFalse(SettingsMenuBuilder.repeatsToolbarTitle(true, "Settings", null));
    }
}
