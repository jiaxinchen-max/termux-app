package com.termux.ui.wine.importer;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.artwork.GameArtworkStore;
import com.termux.ui.wine.data.*;
import com.termux.ui.wine.domain.*;
import com.termux.ui.wine.recovery.GameAssetMutationLock;
import com.termux.ui.wine.runtime.GameContainerFactory;

import java.io.File;
import java.io.IOException;

/** Uncommitted imports never enter the library or the runtime preparation inventory. */
public final class GameImportDrafts {
    private final File files;
    private final GameStoragePaths paths;
    private final File root;

    public GameImportDrafts(File files) {
        this.files = files;
        paths = new GameStoragePaths(files);
        root = new File(paths.getLibraryDirectory().getParentFile(), "import-drafts");
    }

    public FileGameRepository games() { return new FileGameRepository(new File(root, "games")); }
    public FileRuntimeProfileRepository profiles() {
        return new FileRuntimeProfileRepository(new File(root, "profiles"));
    }
    public FileGameContainerRepository containers() {
        return new FileGameContainerRepository(new File(root, "containers"));
    }

    public boolean isDraft(String id) {
        requireId(id);
        return !new File(paths.getLibraryDirectory(), id + ".properties").isFile()
            && new File(new File(root, "games"), id + ".properties").isFile();
    }

    public FileGameRepository editingGames(String id) {
        return isDraft(id) ? games() : new FileGameRepository(paths.getLibraryDirectory());
    }
    public FileRuntimeProfileRepository editingProfiles(String id) {
        return isDraft(id) ? profiles() : new FileRuntimeProfileRepository(paths.getProfilesDirectory());
    }

    /** The library record is the final, atomic publication point. */
    public void commit(Game game, RuntimeProfile profile) throws IOException {
        if (!game.getId().equals(profile.getId())) throw new IllegalArgumentException("import_profile_mismatch");
        GameAssetMutationLock.call(() -> {
            FileGameRepository publishedGames = new FileGameRepository(paths.getLibraryDirectory());
            FileRuntimeProfileRepository publishedProfiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
            FileGameContainerRepository publishedContainers = new FileGameContainerRepository(paths.getContainersDirectory());
            boolean importing = isDraft(game.getId());
            if (!importing && !publishedGames.find(game.getId()).isPresent()) {
                throw new IOException("game_import_draft_missing");
            }
            try {
                publishedProfiles.save(profile);
                if (importing && profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
                    publishedContainers.save(GameContainerFactory.fromProfile(profile, paths));
                }
                publishedGames.save(game);
            } catch (IOException | RuntimeException error) {
                if (importing) {
                    try { publishedProfiles.delete(game.getId()); }
                    catch (IOException cleanup) { error.addSuppressed(cleanup); }
                    if (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
                        try { publishedContainers.delete(profile.getContainerId()); }
                        catch (IOException cleanup) { error.addSuppressed(cleanup); }
                    }
                }
                throw error;
            }
            if (importing) {
                // Publication has succeeded. A cleanup failure must not turn it into a failed Save.
                try { removeMetadata(game.getId()); }
                catch (IOException cleanup) {
                    java.util.logging.Logger.getLogger(GameImportDrafts.class.getName())
                        .warning("Published import draft cleanup failed: " + cleanup.getMessage());
                }
            }
            return null;
        });
    }

    /** Never runs the game uninstaller: shared environments and source files are not draft assets. */
    public void discard(String id) throws IOException {
        GameAssetMutationLock.call(() -> {
            if (!isDraft(id)) return null;
            new GameArtworkStore(files).delete(id);
            removeMetadata(id);
            return null;
        });
    }

    private void removeMetadata(String id) throws IOException {
        RuntimeProfile profile = profiles().find(id).orElse(null);
        if (profile != null && profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
            containers().delete(profile.getContainerId());
        }
        profiles().delete(id);
        games().delete(id);
    }

    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9._-]{1,128}")) throw new IllegalArgumentException("invalid_game_id");
    }
}
