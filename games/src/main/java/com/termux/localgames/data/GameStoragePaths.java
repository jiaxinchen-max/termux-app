package com.termux.localgames.data;

import android.os.Environment;

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
    public File getRuntimeSetupDirectory() {
        return new File(libraryDirectory.getParentFile(), "runtime/setup");
    }
    public File getRuntimeSetupTasksDirectory() {
        return new File(getRuntimeSetupDirectory(), "tasks");
    }
    public File getRuntimeSetupSpecsDirectory() {
        return new File(getRuntimeSetupDirectory(), "specs");
    }
    public File getRuntimeSetupEventsDirectory() {
        return new File(getRuntimeSetupDirectory(), "events");
    }
    public File getRuntimeSetupLogsDirectory() {
        return new File(getRuntimeSetupDirectory(), "logs");
    }
    public File getRuntimeSetupStagingDirectory() {
        return new File(getRuntimeSetupDirectory(), "staging");
    }
    public File getRuntimeRecipeDirectory() {
        return new File(getRuntimeSetupDirectory(), "recipes");
    }
    public File getPrefixSetupDirectory() {
        return new File(libraryDirectory.getParentFile(), "runtime/prefix-setup");
    }
    public File getPrefixSetupTasksDirectory() {
        return new File(getPrefixSetupDirectory(), "tasks");
    }
    public File getPrefixSetupSpecsDirectory() {
        return new File(getPrefixSetupDirectory(), "specs");
    }
    public File getPrefixSetupEventsDirectory() {
        return new File(getPrefixSetupDirectory(), "events");
    }
    public File getPrefixSetupLogsDirectory() {
        return new File(getPrefixSetupDirectory(), "logs");
    }
    public File getTermuxPrefixDirectory() { return new File(filesDirectory, "usr"); }
    public File getProotDistroContainersDirectory() {
        return new File(getTermuxPrefixDirectory(), "var/lib/proot-distro/containers");
    }
    /** The one shared RootFS every ROOTFS_PROOT container's proot session mounts with `-r`
     *  (Winlator-style: extracted once from the base archive, never per-container). Beside
     *  containers/ (never under it, and never containing a containerId) so it is never mistaken
     *  for a per-container directory by reset/backup/asset-scanning code that keys off those. */
    public File getSharedRootfsContainerDirectory() {
        return new File(getTermuxPrefixDirectory(), "var/lib/proot-distro/games-shared-rootfs");
    }
    public File getSharedRootfsDirectory() {
        return new File(getSharedRootfsContainerDirectory(), "rootfs");
    }
    /** Backs the `/root` bind in start_rootfs_game.sh/rootfs_prefix_warmup.sh -- HOME points
     *  inside the shared, conceptually read-only rootfs, so anything written under $HOME needs
     *  its own per-container, per-backend directory here instead (parallel to
     *  getContainerPrefixDirectory). */
    public File getContainerHomeDirectory(String containerId, GameRuntimeBackendType backendType) {
        if (backendType == null) throw new IllegalArgumentException("runtime_backend_required");
        return new File(getContainerDirectory(containerId), "home-" +
            backendType.getStorageValue());
    }
    /** Beside containers/ (never under it), mirroring setup_rootfs_runtime.sh's
     *  TEMPLATE_CACHE_DIR, so it is never mistaken for a container. Holds the per-translator
     *  pre-booted Wine prefix templates (games-rootfs-base-prefix-&lt;translator&gt;.tar.*) and the
     *  recorded recipeSha256 (games-rootfs-base.recipe) -- there is no RootFS base archive here
     *  any more: building/rebuilding the shared RootFS publishes it directly (a same-filesystem
     *  mv, see setup_rootfs_runtime.sh), and compressing it is now the dedicated, explicitly
     *  user-triggered Backup feature below, not a hidden step of every build. */
    public File getTemplateArchiveCacheDirectory() {
        return new File(getTermuxPrefixDirectory(), "var/lib/proot-distro/games-template-cache");
    }
    public File getResetDirectory() {
        return new File(libraryDirectory.getParentFile(), "runtime/reset");
    }
    public File getResetTasksDirectory() {
        return new File(getResetDirectory(), "tasks");
    }
    public File getResetSpecsDirectory() {
        return new File(getResetDirectory(), "specs");
    }
    public File getResetEventsDirectory() {
        return new File(getResetDirectory(), "events");
    }
    public File getResetLogsDirectory() {
        return new File(getResetDirectory(), "logs");
    }
    public File getBackupDirectory() {
        return new File(libraryDirectory.getParentFile(), "runtime/backup");
    }
    public File getBackupTasksDirectory() {
        return new File(getBackupDirectory(), "tasks");
    }
    public File getBackupSpecsDirectory() {
        return new File(getBackupDirectory(), "specs");
    }
    public File getBackupEventsDirectory() {
        return new File(getBackupDirectory(), "events");
    }
    public File getBackupLogsDirectory() {
        return new File(getBackupDirectory(), "logs");
    }
    /** Where a user-triggered base-environment backup is written to / read from. Deliberately
     *  NOT under {@link #filesDirectory} (unlike every other path in this class) -- the whole
     *  point of this feature is to survive an app data clear or uninstall, so it has to live on
     *  real external/shared storage (requires MANAGE_EXTERNAL_STORAGE, already declared in
     *  AndroidManifest.xml; see PermissionUtils). Single fixed name, not one per backup: only the
     *  most recent backup is ever kept (see RootfsBackupForegroundService) -- the backup/restore
     *  UI is a straight overwrite, not a version picker. */
    public File getExternalBackupDirectory() {
        return new File(Environment.getExternalStorageDirectory(), "TermuxGames/backups");
    }
    public File getExternalBackupFile() {
        return new File(getExternalBackupDirectory(), "games-base-backup.tar.zst");
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
