package com.termux.localgames.recovery;

import com.termux.localgames.data.FileLaunchTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.LaunchTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds a fixed-category inventory without traversing SAF or other external content. */
public final class GameAssetInventoryScanner {

    private final GameStoragePaths paths;
    private final FileLaunchTaskRepository tasks;

    public GameAssetInventoryScanner(File filesDirectory) {
        this.paths = new GameStoragePaths(filesDirectory);
        this.tasks = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
    }

    public GameAssetInventory scan(String gameId) throws IOException {
        paths.getGamePrefixDirectory(gameId); // validates id
        List<GameAssetUsage> usages = new ArrayList<>();
        usages.add(directory(GameAssetCategory.PREFIX, paths.getPrefixesDirectory(),
            paths.getGamePrefixDirectory(gameId)));
        usages.add(directory(GameAssetCategory.CACHE, paths.getCacheDirectory(),
            paths.getGameCacheDirectory(gameId)));
        usages.add(diagnostics(gameId));
        usages.add(configuration(gameId));
        usages.add(new GameAssetUsage(GameAssetCategory.EXTERNAL_CONTENT, 0, 0,
            true, false, true));
        return new GameAssetInventory(gameId, usages);
    }

    private GameAssetUsage directory(GameAssetCategory category, File parent, File directory)
        throws IOException {
        FileTreeOperations.requireDirectChild(parent, directory);
        FileTreeOperations.Stats stats = FileTreeOperations.measure(directory,
            FileTreeOperations.INVENTORY_LIMITS);
        return usage(category, stats, directory.exists());
    }

    private GameAssetUsage diagnostics(String gameId) throws IOException {
        Aggregate aggregate = new Aggregate();
        Set<String> seen = new HashSet<>();
        for (LaunchTask task : tasks.list()) {
            if (!gameId.equals(task.getGameId())) continue;
            addFile(aggregate, seen, paths.getLaunchTasksDirectory(),
                new File(paths.getLaunchTasksDirectory(), task.getTaskId() + ".properties"));
            addFile(aggregate, seen, paths.getLaunchSpecsDirectory(),
                new File(paths.getLaunchSpecsDirectory(), task.getTaskId() + ".launchspec"));
            addFile(aggregate, seen, paths.getLaunchEventsDirectory(),
                new File(paths.getLaunchEventsDirectory(), task.getTaskId() + ".jsonl"));
            addFile(aggregate, seen, paths.getLaunchLogsDirectory(),
                new File(paths.getLaunchLogsDirectory(), task.getTaskId() + ".log"));
        }
        return new GameAssetUsage(GameAssetCategory.LOGS, aggregate.bytes, aggregate.files,
            aggregate.files > 0, aggregate.incomplete, false);
    }

    private GameAssetUsage configuration(String gameId) throws IOException {
        Aggregate aggregate = new Aggregate();
        Set<String> seen = new HashSet<>();
        addFile(aggregate, seen, paths.getProfilesDirectory(), paths.getCurrentProfileFile(gameId));
        addFile(aggregate, seen, paths.getProfilesDirectory(),
            paths.getLastSuccessfulProfileFile(gameId));
        return new GameAssetUsage(GameAssetCategory.CONFIGURATION, aggregate.bytes,
            aggregate.files, aggregate.files > 0, aggregate.incomplete, false);
    }

    private static void addFile(Aggregate aggregate, Set<String> seen, File parent, File file)
        throws IOException {
        FileTreeOperations.requireDirectChild(parent, file);
        if (!file.exists()) return;
        String canonical = file.getCanonicalPath();
        if (!seen.add(canonical)) return;
        FileTreeOperations.Stats stats = FileTreeOperations.measure(file,
            FileTreeOperations.INVENTORY_LIMITS);
        aggregate.add(stats);
    }

    private static GameAssetUsage usage(GameAssetCategory category,
                                        FileTreeOperations.Stats stats, boolean exists) {
        return new GameAssetUsage(category, stats.bytes, stats.files, exists,
            stats.incomplete, false);
    }

    private static final class Aggregate {
        long bytes;
        long files;
        boolean incomplete;

        void add(FileTreeOperations.Stats stats) {
            bytes = saturatingAdd(bytes, stats.bytes);
            files = saturatingAdd(files, stats.files);
            incomplete |= stats.incomplete;
        }

        private static long saturatingAdd(long left, long right) {
            return Long.MAX_VALUE - left < right ? Long.MAX_VALUE : left + right;
        }
    }
}
