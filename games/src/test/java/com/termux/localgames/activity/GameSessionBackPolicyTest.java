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

    @Test
    public void drawerLevelDecisionTable() {
        GameSessionBackPolicy.DrawerLevel top = GameSessionBackPolicy.DrawerLevel.TOP;
        GameSessionBackPolicy.DrawerLevel page = GameSessionBackPolicy.DrawerLevel.PROCESS_MANAGER;
        // drawer closed: BACK opens it whatever level was left behind
        assertEquals(GameSessionBackPolicy.Action.OPEN_DRAWER, GameSessionBackPolicy.decide(false, top, false));
        assertEquals(GameSessionBackPolicy.Action.OPEN_DRAWER, GameSessionBackPolicy.decide(false, page, false));
        // drawer open at top: close; inside the process manager sub-page: one level up
        assertEquals(GameSessionBackPolicy.Action.CLOSE_DRAWER, GameSessionBackPolicy.decide(true, top, false));
        assertEquals(GameSessionBackPolicy.Action.NAVIGATE_UP, GameSessionBackPolicy.decide(true, page, false));
        // a dialog (exit question, affinity, new task) handles BACK itself
        for (GameSessionBackPolicy.DrawerLevel level : GameSessionBackPolicy.DrawerLevel.values()) {
            assertEquals(GameSessionBackPolicy.Action.IGNORE, GameSessionBackPolicy.decide(true, level, true));
            assertEquals(GameSessionBackPolicy.Action.IGNORE, GameSessionBackPolicy.decide(false, level, true));
        }
    }

    @Test
    public void processManagerIsASubLevelThatReturnsToTop() {
        assertFalse(GameSessionBackPolicy.DrawerLevel.TOP.isSubLevel());
        assertTrue(GameSessionBackPolicy.DrawerLevel.PROCESS_MANAGER.isSubLevel());
        assertEquals(GameSessionBackPolicy.DrawerLevel.TOP,
            GameSessionBackPolicy.DrawerLevel.PROCESS_MANAGER.up());
        assertEquals(GameSessionBackPolicy.DrawerLevel.TOP, GameSessionBackPolicy.DrawerLevel.TOP.up());
    }

    @Test
    public void subPageBackSequenceWalksUpThenClosesThenReopens() {
        GameSessionBackPolicy.DrawerLevel level = GameSessionBackPolicy.DrawerLevel.PROCESS_MANAGER;
        assertEquals(GameSessionBackPolicy.Action.NAVIGATE_UP, GameSessionBackPolicy.decide(true, level, false));
        level = level.up();
        assertEquals(GameSessionBackPolicy.Action.CLOSE_DRAWER, GameSessionBackPolicy.decide(true, level, false));
        assertEquals(GameSessionBackPolicy.Action.OPEN_DRAWER, GameSessionBackPolicy.decide(false, level, false));
    }
}
