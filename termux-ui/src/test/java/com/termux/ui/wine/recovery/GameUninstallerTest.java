package com.termux.ui.wine.recovery;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.data.FileGameRepository;
import com.termux.ui.wine.data.FileLaunchTaskRepository;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.LaunchStage;
import com.termux.ui.wine.domain.LaunchTask;
import com.termux.ui.wine.domain.LaunchTaskState;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfilePreset;
import com.termux.ui.wine.domain.RuntimeProfilePresets;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GameUninstallerTest {
    private final com.termux.ui.api.LocalGamesHost stoppingHost = new com.termux.ui.api.LocalGamesHost() {
        public boolean isRuntimeAvailable() { return true; }
        public void forceStopRuntimeProcesses(java.util.List<String> paths) {}
    };

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void invalidProfileCanBeRemovedAfterForceStopWithoutChangingExternalFiles() throws Exception {
        Fixture fixture = fixture("invalid-files", "wine-invalid");
        File profile = fixture.paths.getCurrentProfileFile(fixture.gameId);
        String text = new String(java.nio.file.Files.readAllBytes(profile.toPath()), StandardCharsets.UTF_8);
        java.nio.file.Files.write(profile.toPath(), text.replace("xinputEnabled=true", "").getBytes(StandardCharsets.UTF_8));
        new FileRuntimeProfileRepository(fixture.paths.getProfilesDirectory()).saveLastSuccessful(
            RuntimeProfilePresets.create(fixture.gameId, RuntimeProfilePreset.RECOMMENDED));
        final boolean[] killed = {false};
        com.termux.ui.api.LocalGamesHost host = new com.termux.ui.api.LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public void forceStopRuntimeProcesses(java.util.List<String> scopes) {
                assertTrue(profile.isFile());
                assertFalse(scopes.isEmpty());
                killed[0] = true;
            }
        };
        new GameUninstaller(fixture.files, host).execute(GameUninstallPlan.keepPrivateAssets(fixture.gameId));
        assertTrue(killed[0]);
        assertFalse(fixture.games.find(fixture.gameId).isPresent());
        assertFalse(profile.exists());
        assertFalse(new File(fixture.paths.getProfilesDirectory(), fixture.gameId + ".last-success.properties").exists());
        assertTrue(fixture.external.isFile());
    }

    @Test public void failedForceStopKeepsInvalidGameAndProfile() throws Exception {
        Fixture fixture = fixture("stop-failure-files", "wine-invalid");
        File profile = fixture.paths.getCurrentProfileFile(fixture.gameId);
        write(profile, "broken=true");
        com.termux.ui.api.LocalGamesHost host = new com.termux.ui.api.LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public void forceStopRuntimeProcesses(java.util.List<String> scopes) throws com.termux.ui.api.LaunchHostException {
                throw new com.termux.ui.api.LaunchHostException("runtime_force_stop_failed", true, null);
            }
        };
        try {
            new GameUninstaller(fixture.files, host).execute(GameUninstallPlan.keepPrivateAssets(fixture.gameId));
            fail("force-stop failure must abort removal");
        } catch (IOException expected) { assertEquals("runtime_force_stop_failed", expected.getMessage()); }
        assertTrue(profile.isFile());
        assertTrue(fixture.games.find(fixture.gameId).isPresent());
        assertTrue(fixture.external.isFile());
    }

    @Test public void unknownOtherBindingDoesNotBlockRemovalOrProveContainerOrphaned() throws Exception {
        Fixture fixture = fixture("unknown-other-files", "wine-valid");
        FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(fixture.paths.getProfilesDirectory());
        profiles.save(rootfsProfile(fixture.gameId, "container-keep"));
        write(new File(fixture.paths.getProfilesDirectory(), "wine-broken.properties"), "broken=true");
        write(new File(fixture.paths.getProfilesDirectory(), "invalid name.properties"), "broken=true");
        File containerRecord = new File(fixture.paths.getContainersDirectory(), "container-keep.properties");
        write(containerRecord, "keep");
        new GameUninstaller(fixture.files, stoppingHost).execute(GameUninstallPlan.keepPrivateAssets(fixture.gameId));
        assertFalse(fixture.games.find(fixture.gameId).isPresent());
        assertTrue(containerRecord.isFile());
    }

    @Test
    public void defaultUninstallKeepsAllOptionalPrivateAndExternalContent() throws Exception {
        Fixture fixture = fixture("default-files", "game-1");

        new GameUninstaller(fixture.files, stoppingHost).execute(
            GameUninstallPlan.keepPrivateAssets(fixture.gameId));

        assertFalse(fixture.games.find(fixture.gameId).isPresent());
        assertTrue(fixture.paths.getGamePrefixDirectory(fixture.gameId).isDirectory());
        assertTrue(fixture.paths.getGameCacheDirectory(fixture.gameId).isDirectory());
        assertTrue(fixture.paths.getCurrentProfileFile(fixture.gameId).isFile());
        assertTrue(fixture.external.isFile());
    }

    @Test
    public void activeTaskIsCancelledBeforeUninstall() throws Exception {
        File files = temporary.newFolder("active-uninstall-files");
        GameStoragePaths paths = new GameStoragePaths(files);
        FileGameRepository games = new FileGameRepository(paths.getLibraryDirectory());
        games.save(game("game-3", "content://external/game-3"));
        FileLaunchTaskRepository tasks = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
        tasks.save(LaunchTask.queued("active-task", "game-3", 1));
        final boolean[] killed = {false};
        com.termux.ui.api.LocalGamesHost host = new com.termux.ui.api.LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public void forceStopRuntimeProcesses(java.util.List<String> scopes) {
                try {
                    assertTrue(games.find("game-3").isPresent());
                    assertEquals(LaunchTaskState.CANCELLED, tasks.find("active-task").get().getState());
                } catch (IOException error) { throw new AssertionError(error); }
                killed[0] = true;
            }
        };
        new GameUninstaller(files, host).execute(GameUninstallPlan.keepPrivateAssets("game-3"));
        assertTrue(killed[0]); assertFalse(games.find("game-3").isPresent());
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
        File template = new File(paths.getTemplateArchiveCacheDirectory(), "prefix.tar.gz");
        write(template, "shared template");
        new FileRuntimeProfileRepository(paths.getProfilesDirectory())
            .save(rootfsProfile("game-indep", containerId));
        write(new File(paths.getContainersDirectory(), containerId + ".properties"), "id");

        new GameUninstaller(files, stoppingHost).execute(GameUninstallPlan.keepPrivateAssets("game-indep"));

        assertFalse(games.find("game-indep").isPresent());
        assertFalse(new File(paths.getContainersDirectory(), containerId + ".properties").exists());
        assertTrue(template.isFile());
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

        new GameUninstaller(files, stoppingHost).execute(GameUninstallPlan.keepPrivateAssets("game-a"));

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
            com.termux.ui.wine.domain.GameContainer.DEFAULT_ID + ".properties"), "id");

        new GameUninstaller(files, stoppingHost).execute(GameUninstallPlan.keepPrivateAssets("game-glibc"));

        assertFalse(games.find("game-glibc").isPresent());
        assertTrue(new File(paths.getContainersDirectory(),
            com.termux.ui.wine.domain.GameContainer.DEFAULT_ID + ".properties").exists());
    }

    private static RuntimeProfile rootfsProfile(String gameId, String containerId) {
        return new RuntimeProfile(gameId, "rootfs_hangover_11.16", "rootfs-llvmpipe", "rootfs-wined3d",
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
