package com.termux.ui.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class SettingsPageTest {

    @Test
    public void environmentPagesReturnToTheFirstLevel() {
        assertEquals(SettingsPage.ROOT, SettingsPage.NATIVE.parent());
        assertEquals(SettingsPage.ROOT, SettingsPage.PROOT.parent());
    }

    @Test
    public void firstLevelHasNowhereToGoBack() {
        assertNull(SettingsPage.ROOT.parent());
        assertTrue(SettingsPage.ROOT.isRoot());
        assertFalse(SettingsPage.NATIVE.isRoot());
        assertFalse(SettingsPage.PROOT.isRoot());
    }

    @Test
    public void savedOrdinalRoundTrips() {
        for (SettingsPage page : SettingsPage.values()) {
            assertEquals(page, SettingsPage.fromOrdinal(page.ordinal()));
        }
    }

    @Test
    public void unknownSavedOrdinalFallsBackToTheFirstLevel() {
        assertEquals(SettingsPage.ROOT, SettingsPage.fromOrdinal(-1));
        assertEquals(SettingsPage.ROOT, SettingsPage.fromOrdinal(99));
    }
}
