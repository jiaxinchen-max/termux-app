package com.termux.ui.wine.runtime;

import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.api.LaunchHostException;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.*;
import com.termux.ui.wine.domain.*;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Editing/deletion always terminates related work, regardless of its reported state. */
public final class RuntimeEditingStop {
    public static final Object LOCK = new Object();
    private static final Map<String, String> pendingOwners = new HashMap<>();
    private RuntimeEditingStop() {}

    public static void register(String taskId, String owner) {
        synchronized (LOCK) { pendingOwners.put(taskId, owner); }
    }

    public static void requireNotCancelled(File files, String taskId) throws IOException {
        if (marker(files, taskId).isFile()) throw new IOException("runtime_edit_cancelled");
    }

    public static void cancelOwner(File files, String owner) throws IOException {
        synchronized (LOCK) {
            for (Map.Entry<String, String> entry : new HashMap<>(pendingOwners).entrySet()) {
                if (!owner.equals(entry.getValue())) continue;
                writeMarker(files, entry.getKey());
                pendingOwners.remove(entry.getKey());
            }
        }
    }

    public static boolean shellCancelled(File files, String shellName) {
        if (shellName == null) return false;
        String prefix = shellName.startsWith("games-runtime-setup-") ? "games-runtime-setup-"
            : shellName.startsWith("local-games-") ? "local-games-" : null;
        if (prefix == null) return false;
        String id = shellName.substring(prefix.length());
        if (!id.matches("[A-Za-z0-9._-]{1,128}")) return false;
        return marker(files, id).isFile() || ("local-games-".equals(prefix)
            && new File(new GameStoragePaths(files).getLaunchCancelDirectory(), id + ".cancel").isFile());
    }

    public static void game(File files, LocalGamesHost host, String gameId) throws IOException {
        com.termux.ui.wine.api.WineCDriveBackupTasks.stopForMutation(files, gameId);
        synchronized (LOCK) {
            GameStoragePaths paths = new GameStoragePaths(files);
            RuntimeProfileBinding profile = new com.termux.ui.wine.importer.GameImportDrafts(files)
                .editingProfiles(gameId).findBinding(gameId).orElse(null);
            Set<String> games = new HashSet<>(); games.add(gameId);
            FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
            if (profile != null) for (String otherId : profiles.listProfileIds()) {
                RuntimeProfileBinding other;
                try { other = profiles.findBinding(otherId).orElse(null); }
                catch (IOException | IllegalArgumentException unreadableOwnership) { continue; }
                // The target prefix scope below also covers peers with unreadable metadata.
                if (other != null && other.getRuntimeBackendType() == profile.getRuntimeBackendType()
                    && other.getContainerId().equals(profile.getContainerId())
                    && (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT
                        || other.getWinePackage().equals(profile.getWinePackage()))) games.add(other.getId());
            }
            Set<String> owners = new HashSet<>();
            for (String game : games) owners.add("game:" + game);
            if (profile != null) owners.add("container:" + profile.getContainerId());
            List<String> scopes = new ArrayList<>();
            if (profile != null) scopes.add(paths.getContainerPrefixDirectory(profile.getContainerId(),
                profile.getRuntimeBackendType(), profile.getWinePackage()).getAbsolutePath());
            Set<String> taskIds = new HashSet<>();
            for (Map.Entry<String, String> entry : pendingOwners.entrySet())
                if (owners.contains(entry.getValue())) taskIds.add(entry.getKey());
            FilePrefixSetupTaskRepository prefixes = new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory());
            for (PrefixSetupTask task : prefixes.list()) if (games.contains(task.getGameId())) {
                taskIds.add(task.getTaskId());
                if (!task.getState().isTerminal()) prefixes.save(task.transition(RuntimeSetupTaskState.CANCELLED,
                    "runtime_edit_cancelled", Math.max(task.getUpdatedAt(), System.currentTimeMillis())));
            }
            FileRuntimeSetupTaskRepository setups = new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory());
            for (RuntimeSetupTask task : setups.list()) if (profile != null
                    && profile.getContainerId().equals(task.getContainerId()) && !task.isBaseOnly()) {
                taskIds.add(task.getTaskId());
                if (!task.getState().isTerminal()) setups.save(task.transition(RuntimeSetupTaskState.CANCELLED,
                    "runtime_edit_cancelled", Math.max(task.getUpdatedAt(), System.currentTimeMillis())));
            }
            FileLaunchTaskRepository launches = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
            for (LaunchTask task : launches.list()) if (games.contains(task.getGameId())) {
                taskIds.add(task.getTaskId());
                if (!task.getState().isTerminal()) {
                    LaunchEvent event = new LaunchEvent(task.getTaskId(), task.getLastEventSequence() + 1,
                        LaunchTaskState.CANCELLED, LaunchStage.COMPLETE, 100,
                        Math.max(task.getUpdatedAt(), System.currentTimeMillis()), task.getPid(), null,
                        "", false, "runtime_edit_cancelled", task.getLogRef());
                    launches.save(new LaunchTaskStateMachine().apply(task, event));
                }
            }
            for (String id : taskIds) {
                writeMarker(files, id);
                scopes.add(new File(paths.getPrefixSetupSpecsDirectory(), id + ".conf").getAbsolutePath());
                scopes.add(new File(paths.getRuntimeSetupSpecsDirectory(), id + ".setupspec").getAbsolutePath());
                scopes.add(new File(paths.getLaunchSpecsDirectory(), id + ".launchspec").getAbsolutePath());
                pendingOwners.remove(id);
            }
            try { host.forceStopRuntimeProcesses(scopes); }
            catch (LaunchHostException error) { throw new IOException(error.getErrorCode(), error); }
        }
    }

    private static void writeMarker(File files, String id) throws IOException {
        File marker = marker(files, id);
        if (!marker.getParentFile().isDirectory() && !marker.getParentFile().mkdirs()) throw new IOException("runtime_cancel_directory_failed");
        if (!marker.createNewFile() && !marker.isFile()) throw new IOException("runtime_cancel_record_failed");
    }

    private static File marker(File files, String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("invalid_task_id");
        return new File(new GameStoragePaths(files).getRuntimeDirectory(), "edit-cancel/" + taskId);
    }
}
