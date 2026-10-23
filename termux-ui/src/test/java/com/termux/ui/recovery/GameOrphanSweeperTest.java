package com.termux.ui.recovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.ui.data.FileGameRepository;
import com.termux.ui.data.FileLaunchTaskRepository;
import com.termux.ui.data.FileRuntimeProfileRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.Game;
import com.termux.ui.domain.GameContainer;
import com.termux.ui.domain.GameRuntimeBackendType;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.domain.LaunchTask;
import com.termux.ui.domain.RuntimeProfile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

public class GameOrphanSweeperTest {

    private static final long LATER = System.currentTimeMillis() + GameOrphanSweeper.MIN_AGE_MS * 2;

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void removesUnreferencedContainerAndOrphanProfileWithItsMountLink() throws Exception {
        File files = temporary.newFolder("orphans");
        GameStoragePaths paths = new GameStoragePaths(files);
        new FileGameRepository(paths.getLibraryDirectory()).save(game("live"));
        FileRuntimeProfileRepository profiles =
            new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        profiles.save(profile("live", "container-live0001"));
        profiles.save(profile("gone", "container-gone0001"));
        record(paths, "container-live0001");
        record(paths, "container-gone0001");
        record(paths, "container-stray001");
        File mount = new File(paths.getLibraryDirectory().getParentFile(), "mounts/gone");
        write(mount, "link");

        GameOrphanSweeper.Result result = GameOrphanSweeper.sweep(files, LATER);

        assertEquals(1, result.profiles);
        assertEquals(2, result.containers);
        assertTrue(profiles.find("live").isPresent());
        assertFalse(profiles.find("gone").isPresent());
        assertFalse(mount.exists());
        assertTrue(recordFile(paths, "container-live0001").exists());
        assertFalse(recordFile(paths, "container-gone0001").exists());
        assertFalse(recordFile(paths, "container-stray001").exists());
    }

    @Test
    public void keepsTheDefaultContainerAndWhatALiveGameStillUses() throws Exception {
        File files = temporary.newFolder("keep");
        GameStoragePaths paths = new GameStoragePaths(files);
        new FileGameRepository(paths.getLibraryDirectory()).save(game("live"));
        new FileRuntimeProfileRepository(paths.getProfilesDirectory())
            .save(profile("live", "container-live0002"));
        record(paths, GameContainer.DEFAULT_ID);
        record(paths, "container-live0002");

        GameOrphanSweeper.Result result = GameOrphanSweeper.sweep(files, LATER);

        assertEquals(0, result.profiles);
        assertEquals(0, result.containers);
        assertTrue(recordFile(paths, GameContainer.DEFAULT_ID).exists());
        assertTrue(recordFile(paths, "container-live0002").exists());
    }

    @Test
    public void leavesRecentEntriesAlone() throws Exception {
        File files = temporary.newFolder("recent");
        GameStoragePaths paths = new GameStoragePaths(files);
        FileRuntimeProfileRepository profiles =
            new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        profiles.save(profile("importing", "container-new00001"));
        record(paths, "container-new00001");
        record(paths, "container-draft001");

        GameOrphanSweeper.Result result = GameOrphanSweeper.sweep(files, System.currentTimeMillis());

        assertEquals(0, result.profiles);
        assertEquals(0, result.containers);
        assertTrue(profiles.find("importing").isPresent());
        assertTrue(recordFile(paths, "container-new00001").exists());
        assertTrue(recordFile(paths, "container-draft001").exists());
    }

    @Test
    public void deletesNothingWhileATaskIsActive() throws Exception {
        File files = temporary.newFolder("active");
        GameStoragePaths paths = new GameStoragePaths(files);
        new FileLaunchTaskRepository(paths.getLaunchTasksDirectory())
            .save(LaunchTask.queued("task", "live", 1));
        record(paths, "container-gone0003");

        GameOrphanSweeper.Result result = GameOrphanSweeper.sweep(files, LATER);

        assertEquals(0, result.containers);
        assertTrue(recordFile(paths, "container-gone0003").exists());
    }

    @Test
    public void abortsWithoutDeletingWhenTheLibraryCannotBeRead() throws Exception {
        File files = temporary.newFolder("unreadable");
        GameStoragePaths paths = new GameStoragePaths(files);
        write(new File(paths.getLibraryDirectory(), "broken.properties"), "not a game");
        record(paths, "container-gone0004");

        try {
            GameOrphanSweeper.sweep(files, LATER);
            org.junit.Assert.fail("an unreadable library must abort the sweep");
        } catch (IOException expected) {
            // expected
        }
        assertTrue(recordFile(paths, "container-gone0004").exists());
    }

    private static File recordFile(GameStoragePaths paths, String id) {
        return new File(paths.getContainersDirectory(), id + ".properties");
    }

    private static void record(GameStoragePaths paths, String id) throws Exception {
        write(recordFile(paths, id), "id");
    }

    private static RuntimeProfile profile(String gameId, String containerId) {
        return new RuntimeProfile(gameId, "rootfs_hangover_11.16", "rootfs-llvmpipe", "rootfs-wined3d",
            "pulseaudio", "1280x720", "INTERMEDIATE", Collections.emptyMap(), "",
            LaunchExecutionMode.TERMINAL_SESSION, Collections.emptyMap(),
            GameRuntimeBackendType.ROOTFS_PROOT, "debian-13-games-rootfs", containerId);
    }

    private static Game game(String id) {
        return new Game(id, "Game", "content://external/" + id, "Game.exe", ".",
            Collections.emptyList(), "", 0);
    }

    private static void write(File file, String value) throws Exception {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new Exception("mkdir failed");
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
