package com.termux.ui.wine.recovery;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.api.ResolvedGameDirectory;
import com.termux.ui.wine.data.FileGameRepository;
import com.termux.ui.wine.data.FileLaunchTaskRepository;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.GameContainer;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.LaunchTask;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeSetupTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Inventory of the selected configuration, its source folder and shared runtime. Read-only. */
public final class GameAssetInventoryScanner {
    public interface DirectoryResolver { ResolvedGameDirectory resolve(String uri); }
    private final GameStoragePaths paths;
    private final File filesDirectory;
    private final DirectoryResolver resolver;

    public GameAssetInventoryScanner(File filesDirectory, DirectoryResolver resolver) {
        this.filesDirectory = filesDirectory;
        paths = new GameStoragePaths(filesDirectory);
        this.resolver = resolver;
    }

    public GameAssetInventory scan(String gameId) throws IOException {
        paths.getGamePrefixDirectory(gameId); // validates id
        Game game = new FileGameRepository(paths.getLibraryDirectory()).find(gameId)
            .orElseThrow(() -> new IOException("game_not_found"));
        FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        RuntimeProfile profile = profiles.find(gameId)
            .orElseThrow(() -> new IOException("runtime_profile_missing"));
        boolean proot = profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT;
        boolean sharedContainer = GameContainer.DEFAULT_ID.equals(profile.getContainerId());
        for (Game other : new FileGameRepository(paths.getLibraryDirectory()).list()) {
            if (gameId.equals(other.getId())) continue;
            java.util.Optional<RuntimeProfile> otherProfile = profiles.find(other.getId());
            if (otherProfile.isPresent() && profile.getContainerId().equals(otherProfile.get().getContainerId())
                && profile.getRuntimeBackendType() == otherProfile.get().getRuntimeBackendType()) sharedContainer = true;
        }
        ReadOnlyTreeMeasure measure = new ReadOnlyTreeMeasure();
        List<GameAssetUsage> usages = new ArrayList<>();
        ReadOnlyTreeMeasure.Stats prefix = new ReadOnlyTreeMeasure.Stats();
        measure.add(paths.getContainerPrefixDirectory(profile.getContainerId(), profile.getRuntimeBackendType(),
            profile.getWinePackage()), prefix);
        usages.add(usage(GameAssetCategory.PREFIX, prefix, false, sharedContainer));
        ReadOnlyTreeMeasure.Stats container = new ReadOnlyTreeMeasure.Stats();
        // The prefix has already been counted, including hardlinks shared with the home tree.
        measure.add(paths.getContainerDirectory(profile.getContainerId()), container);
        measure.add(new File(paths.getContainersDirectory(), profile.getContainerId() + ".properties"), container);
        usages.add(usage(GameAssetCategory.CONTAINER, container, false, sharedContainer));
        ReadOnlyTreeMeasure.Stats cache = new ReadOnlyTreeMeasure.Stats();
        measure.add(paths.getGameCacheDirectory(gameId), cache);
        usages.add(usage(GameAssetCategory.CACHE, cache, false, false));
        ReadOnlyTreeMeasure.Stats logs = new ReadOnlyTreeMeasure.Stats();
        for (LaunchTask task : new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).list()) {
            if (gameId.equals(task.getGameId())) addTask(measure, logs, task.getTaskId(), paths.getLaunchTasksDirectory(),
                paths.getLaunchSpecsDirectory(), paths.getLaunchEventsDirectory(), paths.getLaunchLogsDirectory());
        }
        for (PrefixSetupTask task : new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).list()) {
            if (gameId.equals(task.getGameId())) addTask(measure, logs, task.getTaskId(), paths.getPrefixSetupTasksDirectory(),
                paths.getPrefixSetupSpecsDirectory(), paths.getPrefixSetupEventsDirectory(), paths.getPrefixSetupLogsDirectory());
        }
        for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory()).list()) {
            if (proot && profile.getContainerId().equals(task.getContainerId())) {
                addTask(measure, logs, task.getTaskId(), paths.getRuntimeSetupTasksDirectory(),
                    paths.getRuntimeSetupSpecsDirectory(), paths.getRuntimeSetupEventsDirectory(), paths.getRuntimeSetupLogsDirectory());
                measure.add(new File(paths.getRuntimeSetupStagingDirectory(), task.getTaskId()), logs);
            }
        }
        usages.add(usage(GameAssetCategory.LOGS, logs, false, false));
        ReadOnlyTreeMeasure.Stats configuration = new ReadOnlyTreeMeasure.Stats();
        measure.add(new File(paths.getLibraryDirectory(), gameId + ".properties"), configuration);
        measure.add(paths.getCurrentProfileFile(gameId), configuration);
        measure.add(paths.getLastSuccessfulProfileFile(gameId), configuration);
        measure.add(new File(paths.getLibraryDirectory().getParentFile(), "artwork/" + gameId + ".cover"), configuration);
        usages.add(usage(GameAssetCategory.CONFIGURATION, configuration, false, false));
        ReadOnlyTreeMeasure.Stats runtime = new ReadOnlyTreeMeasure.Stats();
        if (proot) {
            measure.add(paths.getSharedRootfsContainerDirectory(), runtime);

        } else {
            measure.add(new File(paths.getTermuxPrefixDirectory(), "glibc"), runtime);
        }
        try {
            java.util.Set<String> componentIds = proot
                ? new com.termux.ui.wine.runtime.RootfsProotBackend().requiredComponentIds(profile, paths)
                : new com.termux.ui.wine.runtime.GlibcTermuxBoxBackend().requiredComponentIds(profile, paths);
            File componentRoot = new com.termux.ui.components.ComponentStoragePaths(filesDirectory).getInstallDirectory();
            for (String id : componentIds) measure.add(new File(componentRoot, id), runtime);
        } catch (IllegalArgumentException invalidSelection) {
            runtime.incomplete = true;
        }
        usages.add(usage(GameAssetCategory.SHARED_RUNTIME, runtime, false, true));
        if (proot) {
            ReadOnlyTreeMeasure.Stats buildCache = new ReadOnlyTreeMeasure.Stats();
            measure.add(paths.getRootfsBuildContainerDirectory(), buildCache);
            measure.add(paths.getTemplateArchiveCacheDirectory(), buildCache);
            measure.add(paths.getRuntimeRecipeDirectory(), buildCache);
            usages.add(usage(GameAssetCategory.BUILD_CACHE, buildCache, false, true));
        }
        ReadOnlyTreeMeasure.Stats source = new ReadOnlyTreeMeasure.Stats();
        ResolvedGameDirectory resolved = resolver.resolve(game.getRootUri());
        if (resolved.isResolved()) {
            measure.add(new File(resolved.getPath()), source);
            if (!source.exists) source.incomplete = true;
        } else source.incomplete = true;
        usages.add(usage(GameAssetCategory.EXTERNAL_CONTENT, source, true, false));
        return new GameAssetInventory(gameId, usages);
    }

    private void addTask(ReadOnlyTreeMeasure measure, ReadOnlyTreeMeasure.Stats result,
                                String id, File tasks, File specs, File events, File logs) {
        measure.add(new File(tasks, id + ".properties"), result);
        measure.add(new File(specs, id + ".launchspec"), result);
        measure.add(new File(specs, id + ".spec"), result);
        measure.add(new File(specs, id + ".conf"), result);
        measure.add(new File(specs, id + ".setupspec"), result);
        File preparation = com.termux.ui.service.SetupPreparationLog.file(filesDirectory, id);
        measure.add(preparation, result);
        measure.add(new File(preparation.getPath() + ".error"), result);
        measure.add(new File(events, id + ".jsonl"), result);
        measure.add(new File(logs, id + ".log"), result);
    }

    private static GameAssetUsage usage(GameAssetCategory category, ReadOnlyTreeMeasure.Stats stats,
                                         boolean external, boolean shared) {
        return new GameAssetUsage(category, stats.bytes, stats.files, stats.exists,
            stats.incomplete, external, shared);
    }
}
