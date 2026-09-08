package com.termux.localgames.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.data.FileLaunchTaskRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class GameAssetInventoryScannerTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void classifiesOnlyPrivateOwnedAssetsAndProtectsExternalContent() throws Exception {
        File files = temporary.newFolder("inventory-files");
        GameStoragePaths paths = new GameStoragePaths(files);
        write(new File(paths.getGamePrefixDirectory("game-1"), "drive_c/game.dat"), "12345");
        write(new File(paths.getGameCacheDirectory("game-1"), "shader.bin"), "123");
        new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(
            RuntimeProfilePresets.create("game-1", RuntimeProfilePreset.RECOMMENDED));
        LaunchTask task = LaunchTask.queued("task-1", "game-1", 1)
            .transition(LaunchTaskState.SUCCEEDED, LaunchStage.COMPLETE, 100,
                10, 0, "", false, "log");
        new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).save(task);
        write(new File(paths.getLaunchLogsDirectory(), "task-1.log"), "logs");

        GameAssetInventory inventory = new GameAssetInventoryScanner(files).scan("game-1");

        assertTrue(usage(inventory, GameAssetCategory.PREFIX).getBytes() >= 5);
        assertTrue(usage(inventory, GameAssetCategory.CACHE).getBytes() >= 3);
        assertTrue(usage(inventory, GameAssetCategory.LOGS).getFiles() >= 2);
        assertEquals(1, usage(inventory, GameAssetCategory.CONFIGURATION).getFiles());
        assertTrue(usage(inventory, GameAssetCategory.EXTERNAL_CONTENT).isProtectedExternal());
    }

    private static GameAssetUsage usage(GameAssetInventory inventory, GameAssetCategory category) {
        return inventory.getUsages().stream().filter(item -> item.getCategory() == category)
            .findFirst().orElseThrow(AssertionError::new);
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new Exception("mkdir failed");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
