package com.termux.localgames.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileLaunchTaskRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.Game;
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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class GameUninstallerTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void defaultUninstallKeepsAllOptionalPrivateAndExternalContent() throws Exception {
        Fixture fixture = fixture("default-files", "game-1");

        new GameUninstaller(fixture.files).execute(
            GameUninstallPlan.keepPrivateAssets(fixture.gameId));

        assertFalse(fixture.games.find(fixture.gameId).isPresent());
        assertTrue(fixture.paths.getGamePrefixDirectory(fixture.gameId).isDirectory());
        assertTrue(fixture.paths.getGameCacheDirectory(fixture.gameId).isDirectory());
        assertTrue(fixture.paths.getCurrentProfileFile(fixture.gameId).isFile());
        assertTrue(fixture.external.isFile());
    }

    @Test
    public void explicitSelectionsDeleteOnlyOwnedPrivateAssets() throws Exception {
        Fixture fixture = fixture("selected-files", "game-2");

        new GameUninstaller(fixture.files).execute(
            GameUninstallPlan.keepPrivateAssets(fixture.gameId)
                .withSelections(true, true, true, true));

        assertFalse(fixture.paths.getGamePrefixDirectory(fixture.gameId).exists());
        assertFalse(fixture.paths.getGameCacheDirectory(fixture.gameId).exists());
        assertFalse(fixture.paths.getCurrentProfileFile(fixture.gameId).exists());
        assertFalse(new File(fixture.paths.getLaunchTasksDirectory(), "task-" + fixture.gameId + ".properties").exists());
        assertTrue(fixture.external.isFile());
    }

    @Test
    public void activeTaskBlocksUninstallBeforeAnyMutation() throws Exception {
        File files = temporary.newFolder("active-uninstall-files");
        GameStoragePaths paths = new GameStoragePaths(files);
        FileGameRepository games = new FileGameRepository(paths.getLibraryDirectory());
        games.save(game("game-3", "content://external/game-3"));
        new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).save(
            LaunchTask.queued("active-task", "game-3", 1));

        try {
            new GameUninstaller(files).execute(GameUninstallPlan.keepPrivateAssets("game-3"));
            fail("active task must block uninstall");
        } catch (IOException expected) {
            assertEquals("game_asset_task_active", expected.getMessage());
        }
        assertTrue(games.find("game-3").isPresent());
    }

    private Fixture fixture(String directory, String gameId) throws Exception {
        File files = temporary.newFolder(directory);
        GameStoragePaths paths = new GameStoragePaths(files);
        File external = temporary.newFile(gameId + ".external-save");
        FileGameRepository games = new FileGameRepository(paths.getLibraryDirectory());
        games.save(game(gameId, external.toURI().toString()));
        write(new File(paths.getGamePrefixDirectory(gameId), "drive_c/save.dat"), "save");
        write(new File(paths.getGameCacheDirectory(gameId), "shader.bin"), "cache");
        new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(
            RuntimeProfilePresets.create(gameId, RuntimeProfilePreset.RECOMMENDED));
        String taskId = "task-" + gameId;
        LaunchTask task = LaunchTask.queued(taskId, gameId, 1)
            .transition(LaunchTaskState.SUCCEEDED, LaunchStage.COMPLETE, 100,
                10, 0, "", false, "log");
        new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).save(task);
        write(new File(paths.getLaunchLogsDirectory(), taskId + ".log"), "logs");
        return new Fixture(files, paths, games, external, gameId);
    }

    private static Game game(String id, String rootUri) {
        return new Game(id, "Game", rootUri, "Game.exe", ".", Collections.emptyList(), "", 0);
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new Exception("mkdir failed");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class Fixture {
        final File files;
        final GameStoragePaths paths;
        final FileGameRepository games;
        final File external;
        final String gameId;

        Fixture(File files, GameStoragePaths paths, FileGameRepository games,
                File external, String gameId) {
            this.files = files;
            this.paths = paths;
            this.games = games;
            this.external = external;
            this.gameId = gameId;
        }
    }
}
