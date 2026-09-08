package com.termux.localgames.data;

import com.termux.localgames.domain.GameRuntimeBackendType;

import java.io.File;

public final class GameStoragePaths {

    private final File filesDirectory;
    private final File libraryDirectory;
    private final File profilesDirectory;
    private final File launchesDirectory;

    public GameStoragePaths(File filesDirectory) {
        if (filesDirectory == null) throw new IllegalArgumentException("filesDirectory required");
        this.filesDirectory = filesDirectory;
        libraryDirectory = new File(new File(filesDirectory, "games"), "library");
        profilesDirectory = new File(new File(filesDirectory, "games"), "profiles");
        launchesDirectory = new File(new File(filesDirectory, "games"), "launches");
    }

    public File getLibraryDirectory() { return libraryDirectory; }
    /** User-managed installed games. These files can be launched through direct File I/O. */
    public File getImportedGamesDirectory() {
        return new File(libraryDirectory.getParentFile(), "imports");
    }
    public File getProfilesDirectory() { return profilesDirectory; }
    public File getLaunchesDirectory() { return launchesDirectory; }
    public File getLaunchTasksDirectory() { return new File(launchesDirectory, "tasks"); }
    public File getLaunchSpecsDirectory() { return new File(launchesDirectory, "specs"); }
    public File getLaunchEventsDirectory() { return new File(launchesDirectory, "events"); }
    public File getLaunchLogsDirectory() { return new File(launchesDirectory, "logs"); }
    public File getLaunchLocksDirectory() { return new File(launchesDirectory, "locks"); }
    public File getLaunchCancelDirectory() { return new File(launchesDirectory, "cancel"); }
    public File getPrefixesDirectory() { return new File(libraryDirectory.getParentFile(), "prefixes"); }
    public File getContainersDirectory() { return new File(libraryDirectory.getParentFile(), "containers"); }
    public File getContainerDirectory(String containerId) {
        return new File(getContainersDirectory(), requireId(containerId));
    }
    /** The container owns one Wine prefix/C drive shared only by its bound games. */
    public File getContainerPrefixDirectory(String containerId) {
        return new File(getContainerDirectory(containerId), "prefix");
    }

    /**
     * A prefix is binary-runtime-specific.  A GLIBC Wine prefix cannot be opened by a
     * RootFS/Hangover Wine process (and vice versa), because system DLL registrations
     * and loader architecture are persisted inside the prefix.
     *
     * Keep the original path for GLIBC so existing Termux-box containers are not moved.
     */
    public File getContainerPrefixDirectory(String containerId,
                                            GameRuntimeBackendType backendType) {
        if (backendType == null) throw new IllegalArgumentException("runtime_backend_required");
        if (backendType == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
            return getContainerPrefixDirectory(containerId);
        }
        return new File(getContainerDirectory(containerId), "prefix-" +
            backendType.getStorageValue());
    }
    public File getCacheDirectory() { return new File(libraryDirectory.getParentFile(), "cache"); }
    public File getRuntimeDirectory() { return new File(libraryDirectory.getParentFile(), "runtime"); }
    public File getRootfsRuntimeDirectory() {
        return new File(libraryDirectory.getParentFile(), "runtimes/rootfs");
    }
    public File getRuntimeProvisionDirectory() {
        return new File(libraryDirectory.getParentFile(), "runtime/provision");
    }
    public File getRuntimeProvisionTasksDirectory() {
        return new File(getRuntimeProvisionDirectory(), "tasks");
    }
    public File getRuntimeProvisionSpecsDirectory() {
        return new File(getRuntimeProvisionDirectory(), "specs");
    }
    public File getRuntimeProvisionEventsDirectory() {
        return new File(getRuntimeProvisionDirectory(), "events");
    }
    public File getRuntimeProvisionLogsDirectory() {
        return new File(getRuntimeProvisionDirectory(), "logs");
    }
    public File getRuntimeProvisionStagingDirectory() {
        return new File(getRuntimeProvisionDirectory(), "staging");
    }
    public File getRuntimeRecipeDirectory() {
        return new File(getRuntimeProvisionDirectory(), "recipes");
    }
    public File getPrefixProvisionDirectory() {
        return new File(libraryDirectory.getParentFile(), "runtime/prefix-provision");
    }
    public File getPrefixProvisionTasksDirectory() {
        return new File(getPrefixProvisionDirectory(), "tasks");
    }
    public File getPrefixProvisionSpecsDirectory() {
        return new File(getPrefixProvisionDirectory(), "specs");
    }
    public File getPrefixProvisionEventsDirectory() {
        return new File(getPrefixProvisionDirectory(), "events");
    }
    public File getPrefixProvisionLogsDirectory() {
        return new File(getPrefixProvisionDirectory(), "logs");
    }
    public File getTermuxPrefixDirectory() { return new File(filesDirectory, "usr"); }
    public File getProotDistroContainersDirectory() {
        return new File(getTermuxPrefixDirectory(), "var/lib/proot-distro/containers");
    }
    public File getGamePrefixDirectory(String gameId) {
        return new File(getPrefixesDirectory(), requireId(gameId));
    }
    public File getGamePrefixDirectory(String gameId, GameRuntimeBackendType backendType) {
        if (backendType == null) throw new IllegalArgumentException("runtime_backend_required");
        if (backendType == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
            return getGamePrefixDirectory(gameId);
        }
        return new File(new File(getPrefixesDirectory(), backendType.getStorageValue()),
            requireId(gameId));
    }
    public File getGameCacheDirectory(String gameId) {
        return new File(getCacheDirectory(), requireId(gameId));
    }
    public File getCurrentProfileFile(String gameId) {
        return new File(profilesDirectory, requireId(gameId) + ".properties");
    }
    public File getLastSuccessfulProfileFile(String gameId) {
        return new File(profilesDirectory, requireId(gameId) + ".last-success.properties");
    }

    private static String requireId(String gameId) {
        if (gameId == null || !gameId.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new IllegalArgumentException("invalid_game_id");
        }
        return gameId;
    }
}
