package com.termux.ui.activity;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class FileManagerViewModeTest {

    @Test
    public void storageValuesRoundTrip() {
        for (FileManagerViewMode mode : FileManagerViewMode.values()) {
            assertEquals(mode, FileManagerViewMode.fromStorageValue(mode.getStorageValue()));
        }
    }

    @Test
    public void unknownOrMissingValueFallsBackToGrid() {
        assertEquals(FileManagerViewMode.GRID, FileManagerViewMode.fromStorageValue(null));
        assertEquals(FileManagerViewMode.GRID, FileManagerViewMode.fromStorageValue(""));
        assertEquals(FileManagerViewMode.GRID, FileManagerViewMode.fromStorageValue("tiles"));
    }

    @Test
    public void toggleAlternatesBetweenModes() {
        assertEquals(FileManagerViewMode.LIST, FileManagerViewMode.GRID.toggled());
        assertEquals(FileManagerViewMode.GRID, FileManagerViewMode.LIST.toggled());
    }
}
