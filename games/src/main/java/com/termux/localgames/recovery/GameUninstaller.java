package com.termux.localgames.recovery;

import com.termux.localgames.artwork.GameArtworkStore;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileLaunchTaskRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.LaunchTask;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Deletes only proven app-private assets and publishes library removal last. */
public final class GameUninstaller {

    private final GameStoragePaths paths;
    private final FileLaunchTaskRepository tasks;
    private final FileGameRepository games;
    private final FileRuntimeProfileRepository profiles;
    private final GameArtworkStore artwork;

    public GameUninstaller(File filesDirectory) {
        paths = new GameStoragePaths(filesDirectory);
        tasks = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
        games = new FileGameRepository(paths.getLibraryDirectory());
        profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        artwork = new GameArtworkStore(filesDirectory);
    }

    public void execute(GameUninstallPlan plan) throws IOException {
        GameAssetMutationLock.call(() -> {
            executeLocked(plan);
            return null;
        });
    }

    private void executeLocked(GameUninstallPlan plan) throws IOException {
        if (plan == null) throw new IllegalArgumentException("uninstall plan required");
        String gameId = plan.getGameId();
        if (tasks.findActiveForGame(gameId).isPresent()) {
            throw new IOException("game_asset_task_active");
        }
        if (!plan.keepsExternalContent()) throw new IOException("external_content_delete_forbidden");

        if (plan.removesPrefix()) deleteOwnedDirectory(paths.getPrefixesDirectory(),
            paths.getGamePrefixDirectory(gameId));
        if (plan.removesCache()) deleteOwnedDirectory(paths.getCacheDirectory(),
            paths.getGameCacheDirectory(gameId));
        if (plan.removesConfiguration()) profiles.delete(gameId);
        if (plan.removesLogs()) deleteDiagnostics(gameId);

        try (GameArtworkStore.Mutation removal = artwork.stageRemoval(gameId)) {
            games.delete(gameId);
            removal.commit();
        }
    }

    private void deleteDiagnostics(String gameId) throws IOException {
        List<LaunchTask> snapshots = tasks.list();
        for (LaunchTask task : snapshots) {
            if (!gameId.equals(task.getGameId())) continue;
            deleteOwnedFile(paths.getLaunchSpecsDirectory(),
                new File(paths.getLaunchSpecsDirectory(), task.getTaskId() + ".launchspec"));
            deleteOwnedFile(paths.getLaunchEventsDirectory(),
                new File(paths.getLaunchEventsDirectory(), task.getTaskId() + ".jsonl"));
            deleteOwnedFile(paths.getLaunchLogsDirectory(),
                new File(paths.getLaunchLogsDirectory(), task.getTaskId() + ".log"));
            deleteOwnedFile(paths.getLaunchCancelDirectory(),
                new File(paths.getLaunchCancelDirectory(), task.getTaskId() + ".cancel"));
            deleteOwnedDirectory(paths.getLaunchLocksDirectory(),
                new File(paths.getLaunchLocksDirectory(), task.getTaskId()));
            deleteOwnedFile(paths.getLaunchTasksDirectory(),
                new File(paths.getLaunchTasksDirectory(), task.getTaskId() + ".properties"));
        }
    }

    private static void deleteOwnedDirectory(File parent, File target) throws IOException {
        FileTreeOperations.requireDirectChild(parent, target);
        if (target.exists()) FileTreeOperations.deleteTree(target);
    }

    private static void deleteOwnedFile(File parent, File target) throws IOException {
        FileTreeOperations.requireDirectChild(parent, target);
        if (target.exists() && !target.delete()) throw new IOException("private_asset_delete_failed");
    }
}
