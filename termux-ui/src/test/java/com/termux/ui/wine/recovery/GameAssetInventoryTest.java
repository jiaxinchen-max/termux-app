package com.termux.ui.wine.recovery;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;
public class GameAssetInventoryTest {
    @Test public void separatesExclusiveSharedAndSourceAndReportsPartial() {
        GameAssetInventory inventory = new GameAssetInventory("game", Arrays.asList(
            new GameAssetUsage(GameAssetCategory.CONFIGURATION, 10, 1, true, false, false),
            new GameAssetUsage(GameAssetCategory.PREFIX, 20, 1, true, false, false, true),
            new GameAssetUsage(GameAssetCategory.SHARED_RUNTIME, 30, 1, true, false, false, true),
            new GameAssetUsage(GameAssetCategory.EXTERNAL_CONTENT, 40, 1, true, true, true)));
        assertEquals(10, inventory.getPrivateBytes());
        assertEquals(50, inventory.getSharedBytes());
        assertEquals(40, inventory.getContentBytes());
        assertTrue(inventory.isIncomplete());
    }
}
