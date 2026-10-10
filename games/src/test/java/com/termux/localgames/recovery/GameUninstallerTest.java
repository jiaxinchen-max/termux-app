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
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;
import com.termux.localgames.domain.RuntimeProfile;
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

    @Test
    public void uninstallDeletesBoundIndependentContainerRecord() throws Exception {
        // deleteTree() needs Android's Os.lstat (unavailable on the host JVM, same limitation
        // the other recovery tests hit), so this exercises only the record-level cascade: the
        // rootfs/proot directories are left absent so their deletion is skipped, and we assert
        // the independent container's inventory record is removed with the game.
        File files = temporary.newFolder("indep-container-files");
        GameStoragePaths paths = new GameStoragePaths(files);
        FileGameRepository games = new FileGameRepository(paths.getLibraryDirectory());
        games.save(game("game-indep", "content://external/game-indep"));
        String containerId = "container-indep01";
        new FileRuntimeProfileRepository(paths.getProfilesDirectory())
            .save(rootfsProfile("game-indep", containerId));
        write(new File(paths.getContainersDirectory(), containerId + ".properties"), "id");

        new GameUninstaller(files).execute(GameUninstallPlan.keepPrivateAssets("game-indep"));

        assertFalse(games.find("game-indep").isPresent());
        assertFalse(new File(paths.getContainersDirectory(), containerId + ".properties").exists());
    }

    @Test
    public void uninstallKeepsContainerStillReferencedByAnotherGame() throws Exception {
        File files = temporary.newFolder("shared-container-files");
        GameStoragePaths paths = new GameStoragePaths(files);
        FileGameRepository games = new FileGameRepository(paths.getLibraryDirectory());
        games.save(game("game-a", "content://external/game-a"));
        games.save(game("game-b", "content://external/game-b"));
        String containerId = "container-shared01";
        FileRuntimeProfileRepository profiles =
            new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        profiles.save(rootfsProfile("game-a", containerId));
        profiles.save(rootfsProfile("game-b", containerId));
        write(new File(paths.getContainersDirectory(), containerId + ".properties"), "id");

        new GameUninstaller(files).execute(GameUninstallPlan.keepPrivateAssets("game-a"));

        assertFalse(games.find("game-a").isPresent());
        // game-b still uses it, so the shared container record must survive.
        assertTrue(new File(paths.getContainersDirectory(), containerId + ".properties").exists());
    }

    @Test
    public void uninstallKeepsTheSharedGlobalContainer() throws Exception {
        // RECOMMENDED preset binds to GameContainer.DEFAULT_ID -- the global shared container
        // must never be deleted when a GLIBC game is removed.
        File files = temporary.newFolder("default-container-files");
        GameStoragePaths paths = new GameStoragePaths(files);
        FileGameRepository games = new FileGameRepository(paths.getLibraryDirectory());
        games.save(game("game-glibc", "content://external/game-glibc"));
        new FileRuntimeProfileRepository(paths.getProfilesDirectory())
            .save(RuntimeProfilePresets.create("game-glibc", RuntimeProfilePreset.RECOMMENDED));
        write(new File(paths.getContainersDirectory(),
            com.termux.localgames.domain.GameContainer.DEFAULT_ID + ".properties"), "id");

        new GameUninstaller(files).execute(GameUninstallPlan.keepPrivateAssets("game-glibc"));

        assertFalse(games.find("game-glibc").isPresent());
        assertTrue(new File(paths.getContainersDirectory(),
            com.termux.localgames.domain.GameContainer.DEFAULT_ID + ".properties").exists());
    }

    private static RuntimeProfile rootfsProfile(String gameId, String containerId) {
        return new RuntimeProfile(gameId, "hangover-latest", "rootfs-llvmpipe", "rootfs-wined3d",
            "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap(), "",
            LaunchExecutionMode.TERMINAL_SESSION, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", containerId);
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
