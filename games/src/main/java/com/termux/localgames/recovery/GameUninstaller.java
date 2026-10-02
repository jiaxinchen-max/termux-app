package com.termux.localgames.recovery;

import com.termux.localgames.artwork.GameArtworkStore;
import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileLaunchTaskRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.RuntimeProfile;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;

/** Deletes only proven app-private assets and publishes library removal last. */
public final class GameUninstaller {

    private final GameStoragePaths paths;
    private final FileLaunchTaskRepository tasks;
    private final FileGameRepository games;
    private final FileRuntimeProfileRepository profiles;
    private final FileGameContainerRepository containers;
    private final GameArtworkStore artwork;

    public GameUninstaller(File filesDirectory) {
        paths = new GameStoragePaths(filesDirectory);
        tasks = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
        games = new FileGameRepository(paths.getLibraryDirectory());
        profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        containers = new FileGameContainerRepository(paths.getContainersDirectory());
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
        if (plan.removesLogs()) deleteDiagnostics(gameId);

        // A game's bound container is its private runtime (the Debian RootFS / Wine prefix built
        // for it), not user content, so deleting the game must reclaim it -- otherwise each
        // removed game leaks a multi-GB rootfs. Resolve the container before the profile goes
        // away, then remove it only when it is genuinely this game's own and not shared.
        deleteBoundContainerIfOrphaned(gameId);

        if (plan.removesConfiguration()) profiles.delete(gameId);

        try (GameArtworkStore.Mutation removal = artwork.stageRemoval(gameId)) {
            games.delete(gameId);
            removal.commit();
        }
    }

    /** Removes the independent container bound to {@code gameId} -- its container record, its
     *  per-container prefix/metadata, and its proot-distro RootFS directory -- but only when the
     *  container is not the shared global container and no other game still references it. */
    private void deleteBoundContainerIfOrphaned(String gameId) throws IOException {
        Optional<RuntimeProfile> profile = profiles.find(gameId);
        if (!profile.isPresent()) return;
        String containerId = profile.get().getContainerId();
        if (containerId == null || containerId.isEmpty()
            || GameContainer.DEFAULT_ID.equals(containerId)) {
            return;
        }
        if (isContainerReferencedByOtherGame(gameId, containerId)) return;

        // The container's own working directory -- GLIBC prefix, or the RootFS backend's
        // prefix-rootfs_proot/home-rootfs_proot pair (see GameStoragePaths). The RootFS payload
        // itself is never per-container anymore -- every container mounts the one shared,
        // always-current image (see GameStoragePaths.getSharedRootfsDirectory()), so there is no
        // multi-GB per-container copy left to reclaim here.
        deleteTreeIfExists(paths.getContainerDirectory(containerId));
        // The container inventory record itself, removed last as the source of truth.
        containers.delete(containerId);
    }

    private boolean isContainerReferencedByOtherGame(String gameId, String containerId)
        throws IOException {
        for (RuntimeProfile other : profiles.list()) {
            if (gameId.equals(other.getId())) continue;
            if (containerId.equals(other.getContainerId())) return true;
        }
        return false;
    }

    private static void deleteTreeIfExists(File target) throws IOException {
        if (target.exists()) FileTreeOperations.deleteTree(target);
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
