package com.termux.localgames.runtime;

import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.LaunchSpec;
import com.termux.localgames.domain.RuntimeProfile;

import java.io.File;
import java.io.IOException;

/** Freezes validated game/profile/host path state into private task paths. */
public final class LaunchSpecFactory {

    private final GameStoragePaths paths;

    public LaunchSpecFactory(GameStoragePaths paths) {
        if (paths == null) throw new IllegalArgumentException("paths required");
        this.paths = paths;
    }

    public LaunchSpec create(String taskId, Game game, RuntimeProfile profile,
                             String resolvedGameRoot) throws IOException {
        return create(taskId, game, profile, resolvedGameRoot,
            new GlibcTermuxBoxBackend(), "");
    }

    public LaunchSpec create(String taskId, Game game, RuntimeProfile profile,
                             String resolvedGameRoot, GameRuntimeBackend backend,
                             String runtimeRootPath) throws IOException {
        if (!game.getId().equals(profile.getId())) {
            throw new IllegalArgumentException("game_profile_mismatch");
        }
        if (backend == null || backend.getType() != profile.getRuntimeBackendType()) {
            throw new IllegalArgumentException("runtime_backend_profile_mismatch");
        }
        File root = new File(resolvedGameRoot).getCanonicalFile();
        File executable = new File(root, game.getExecutable()).getCanonicalFile();
        File workingDirectory = ".".equals(game.getWorkingDirectory()) ? root :
            new File(root, game.getWorkingDirectory()).getCanonicalFile();
        requireContained(root, executable, "game_executable_outside_root");
        requireContained(root, workingDirectory, "game_workdir_outside_root");
        if (!executable.isFile() || !executable.canRead()) throw new IOException("game_executable_unreadable");
        if (!workingDirectory.isDirectory() || !workingDirectory.canRead()) {
            throw new IOException("game_workdir_unreadable");
        }
        return new LaunchSpec(taskId, game.getId(), root.getPath(), game.getExecutable(),
            game.getWorkingDirectory(), game.getArguments(),
            backend.resolvePrefix(paths, profile).getCanonicalPath(),
            profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(), profile.getAudioDriver(),
            profile.getResolution(), profile.getBox64Preset(), profile.getInputProfileId(),
            profile.getLaunchExecutionMode(),
            profile.getEnvironment(),
            new File(paths.getLaunchEventsDirectory(), taskId + ".jsonl").getCanonicalPath(),
            new File(paths.getLaunchLogsDirectory(), taskId + ".log").getCanonicalPath(),
            new File(paths.getLaunchLocksDirectory(), taskId).getCanonicalPath(),
            new File(paths.getLaunchCancelDirectory(), taskId + ".cancel").getCanonicalPath(),
            43200, profile.getRuntimeBackendType(), profile.getRootfsPackage(),
            runtimeRootPath);
    }

    private static void requireContained(File root, File child, String error) throws IOException {
        if (!(child.equals(root) || child.getPath().startsWith(root.getPath() + File.separator))) {
            throw new IOException(error);
        }
    }
}
