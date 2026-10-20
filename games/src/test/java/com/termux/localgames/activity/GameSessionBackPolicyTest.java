package com.termux.localgames.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameSessionBackPolicyTest {

    @Test
    public void closedDrawerIsOpened() {
        assertEquals(GameSessionBackPolicy.Action.OPEN_DRAWER,
            GameSessionBackPolicy.decide(false, false, false));
    }

    @Test
    public void openDrawerInASubLevelGoesUpFirst() {
        assertEquals(GameSessionBackPolicy.Action.NAVIGATE_UP,
            GameSessionBackPolicy.decide(true, true, false));
    }

    @Test
    public void openDrawerAtTheTopLevelIsClosed() {
        assertEquals(GameSessionBackPolicy.Action.CLOSE_DRAWER,
            GameSessionBackPolicy.decide(true, false, false));
    }

    @Test
    public void aClosedDrawerHasNoSubLevelToLeave() {
        // A stale sub-level flag must not stop BACK from opening the drawer.
        assertEquals(GameSessionBackPolicy.Action.OPEN_DRAWER,
            GameSessionBackPolicy.decide(false, true, false));
    }

    @Test
    public void aDialogShowingOrPendingIgnoresBackWhateverTheDrawerState() {
        for (boolean drawerOpen : new boolean[] {false, true}) {
            for (boolean subLevel : new boolean[] {false, true}) {
                assertEquals(GameSessionBackPolicy.Action.IGNORE,
                    GameSessionBackPolicy.decide(drawerOpen, subLevel, true));
            }
        }
    }

    @Test
    public void backNeverAsksToExit() {
        for (GameSessionBackPolicy.Action action : GameSessionBackPolicy.Action.values()) {
            assertFalse(action.name().contains("EXIT"));
        }
    }

    @Test
    public void repeatsInsideTheWindowAreDuplicates() {
        assertTrue(GameSessionBackPolicy.isDuplicate(1000, 1000));
        assertTrue(GameSessionBackPolicy.isDuplicate(1000, 1000 + GameSessionBackPolicy.DUPLICATE_WINDOW_MS - 1));
    }

    @Test
    public void laterOrFirstPressesAreNotDuplicates() {
        assertFalse(GameSessionBackPolicy.isDuplicate(-1, 5));
        assertFalse(GameSessionBackPolicy.isDuplicate(1000, 1000 + GameSessionBackPolicy.DUPLICATE_WINDOW_MS));
        assertFalse(GameSessionBackPolicy.isDuplicate(1000, 999));
    }
}
