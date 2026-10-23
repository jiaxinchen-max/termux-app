package com.termux.ui.recovery;

import com.termux.ui.data.FileGameContainerRepository;
import com.termux.ui.data.FileGameRepository;
import com.termux.ui.data.FileLaunchTaskRepository;
import com.termux.ui.data.FilePrefixSetupTaskRepository;
import com.termux.ui.data.FileRuntimeProfileRepository;
import com.termux.ui.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.Game;
import com.termux.ui.domain.GameContainer;
import com.termux.ui.domain.LaunchTask;
import com.termux.ui.domain.PrefixSetupTask;
import com.termux.ui.domain.RuntimeProfile;
import com.termux.ui.domain.RuntimeSetupTask;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reclaims what removed games and abandoned edits left behind: profiles and mount links of games that
 * are no longer in the library, and containers (record and working directory) that no remaining game's
 * profile uses. The default container is never touched, nothing is deleted while a launch or setup task
 * is active or when the library cannot be read, and entries modified within {@link #MIN_AGE_MS} are left
 * alone because an import, a setup or an open Runtime page may still be about to reference them.
 */
public final class GameOrphanSweeper {

    static final long MIN_AGE_MS = 15L * 60 * 1000;
    private static final String SUFFIX = ".properties";
    private static final String CONTAINER_PREFIX = "container-";

    /** What one sweep removed. */
    public static final class Result {
        public final int profiles;
        public final int containers;

        Result(int profiles, int containers) {
            this.profiles = profiles;
            this.containers = containers;
        }
    }

    private GameOrphanSweeper() {}

    public static Result sweep(File filesDirectory) throws IOException {
        return sweep(filesDirectory, System.currentTimeMillis());
    }

    static Result sweep(File filesDirectory, long now) throws IOException {
        return GameAssetMutationLock.call(() -> sweepLocked(filesDirectory, now));
    }

    private static Result sweepLocked(File filesDirectory, long now) throws IOException {
        GameStoragePaths paths = new GameStoragePaths(filesDirectory);
        if (anyTaskActive(paths)) return new Result(0, 0);

        Set<String> gameIds = new HashSet<>();
        for (Game game : new FileGameRepository(paths.getLibraryDirectory()).list()) {
            gameIds.add(game.getId());
        }
        FileRuntimeProfileRepository profiles =
            new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        File mounts = new File(paths.getLibraryDirectory().getParentFile(), "mounts");

        Set<String> usedContainers = new HashSet<>();
        int removedProfiles = 0;
        for (RuntimeProfile profile : profiles.list()) {
            if (gameIds.contains(profile.getId())) {
                usedContainers.add(profile.getContainerId());
            } else if (isOld(new File(paths.getProfilesDirectory(), profile.getId() + SUFFIX), now)) {
                profiles.delete(profile.getId());
                new File(mounts, profile.getId()).delete();
                removedProfiles++;
            } else {
                // A recent profile without a game may belong to an import in progress; its container stays.
                usedContainers.add(profile.getContainerId());
            }
        }
        return new Result(removedProfiles, sweepContainers(paths, usedContainers, now));
    }

    private static int sweepContainers(GameStoragePaths paths, Set<String> used, long now)
        throws IOException {
        File directory = paths.getContainersDirectory();
        File[] entries = directory.listFiles();
        if (entries == null) return 0;
        FileGameContainerRepository containers = new FileGameContainerRepository(directory);
        Set<String> handled = new HashSet<>();
        int removed = 0;
        for (File entry : entries) {
            String name = entry.getName();
            String id = name.endsWith(SUFFIX) ? name.substring(0, name.length() - SUFFIX.length()) : name;
            if (!id.startsWith(CONTAINER_PREFIX) || GameContainer.DEFAULT_ID.equals(id)
                || used.contains(id) || !handled.add(id)) {
                continue;
            }
            File record = new File(directory, id + SUFFIX);
            File working = paths.getContainerDirectory(id);
            if (isOld(record, now) && isOld(working, now)) {
                if (working.exists()) FileTreeOperations.deleteTree(working);
                containers.delete(id);
                removed++;
            }
        }
        return removed;
    }

    /** A file or directory that does not exist counts as old: there is nothing recent to protect. */
    private static boolean isOld(File file, long now) {
        return !file.exists() || now - file.lastModified() >= MIN_AGE_MS;
    }

    private static boolean anyTaskActive(GameStoragePaths paths) throws IOException {
        for (LaunchTask task : new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).list()) {
            if (!task.getState().isTerminal()) return true;
        }
        for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(
                paths.getRuntimeSetupTasksDirectory()).list()) {
            if (!task.getState().isTerminal()) return true;
        }
        for (PrefixSetupTask task : new FilePrefixSetupTaskRepository(
                paths.getPrefixSetupTasksDirectory()).list()) {
            if (!task.getState().isTerminal()) return true;
        }
        return false;
    }
}
