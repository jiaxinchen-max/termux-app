package com.termux.ui.wine.importer;

import com.termux.ui.wine.data.GameContainerRepository;
import com.termux.ui.wine.data.GameRepository;
import com.termux.ui.wine.data.RuntimeProfileRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.GameContainer;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfilePreset;
import com.termux.ui.wine.domain.RuntimeProfilePresets;
import com.termux.ui.wine.runtime.GameContainerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

/** Stages an independent draft for each import, even when the EXE is already in the library.
 * The chosen file supplies the root and executable; it never identifies an existing configuration.
 * Runtime preparation starts when the user saves the imported configuration. */
public final class QuickGameImport {

    private QuickGameImport() {}

    public static String importGame(File filesDirectory, File pickedFile, GameRuntimeBackendType backend)
            throws IOException {
        GameImportDrafts drafts = new GameImportDrafts(filesDirectory);
        File parent = pickedFile.getParentFile();
        if (parent == null) throw new IOException("game_root_missing");
        return createConfiguration(drafts.games(), drafts.profiles(), drafts.containers(), parent.toURI().toASCIIString(),
            pickedFile.getName(), backend);
    }

    /** Creates an ordinary editable draft without requiring a user executable. */
    public static String createContainer(File filesDirectory, String name) throws IOException {
        File root = new File(com.termux.ui.data.FrontStorage.root(filesDirectory), "wine-browser-root");
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("game_root_missing");
        GameImportDrafts drafts = new GameImportDrafts(filesDirectory);
        String id = createConfiguration(drafts.games(), drafts.profiles(), drafts.containers(),
            root.toURI().toASCIIString(), Game.WINE_FILE_BROWSER, GameRuntimeBackendType.ROOTFS_PROOT);
        Game game = drafts.games().find(id).orElseThrow(() -> new IOException("game_missing"));
        drafts.games().save(game.withLaunchDetails(name, ".", Collections.emptyList()));
        return id;
    }

    /** Changes only the launch entry; container identity, prefix and runtime profile stay intact. */
    public static void setEntry(File filesDirectory, String id, File pickedFile) throws IOException {
        com.termux.ui.data.GameStoragePaths paths = new com.termux.ui.data.GameStoragePaths(filesDirectory);
        com.termux.ui.wine.recovery.GameAssetMutationLock.call(() -> {
            com.termux.ui.wine.data.FileRuntimeProfileRepository profiles =
                new com.termux.ui.wine.data.FileRuntimeProfileRepository(paths.getProfilesDirectory());
            RuntimeProfile profile = profiles.find(id).orElseThrow(() -> new IOException("game_profile_missing"));
            if (profile.getRuntimeBackendType() != GameRuntimeBackendType.ROOTFS_PROOT)
                throw new IOException("proot_container_required");
            com.termux.ui.wine.data.FileGameRepository games =
                new com.termux.ui.wine.data.FileGameRepository(paths.getLibraryDirectory());
            Game before = games.find(id).orElseThrow(() -> new IOException("game_missing"));
            File file = pickedFile.getCanonicalFile();
            if (!file.isFile() || !file.canRead()) throw new IOException("game_executable_unreadable");
            GameImportValidation.requireSafeGamePaths(file.getName(), ".");
            games.save(new Game(before.getId(), before.getName(), file.getParentFile().toURI().toASCIIString(),
                file.getName(), ".", Collections.emptyList(), before.getArtworkUri(),
                before.getLastPlayedAt(), before.getCreatedAt()));
            return null;
        });
    }

    static String createConfiguration(GameRepository games, RuntimeProfileRepository profiles,
            GameContainerRepository containers, String rootUri, String executable,
            GameRuntimeBackendType backend) throws IOException {
        if (backend == null) throw new IllegalArgumentException("runtimeBackendRequired");
        if (Game.WINE_FILE_BROWSER.equals(executable) && backend != GameRuntimeBackendType.ROOTFS_PROOT)
            throw new IllegalArgumentException("proot_container_required");
        GameImportValidation.requireSafeGamePaths(executable, ".");
        String id = UUID.randomUUID().toString();
        Game game = new Game(id, defaultGameName(executable), rootUri, executable, ".",
            Collections.emptyList(), "", 0);
        RuntimeProfile profile = RuntimeProfilePresets.create(id, RuntimeProfilePreset.RECOMMENDED);
        switch (backend) {
            case GLIBC_TERMUX_BOX:
                // Native configurations keep their own launch settings and share the existing
                // Native runtime/prefix. Importing never rewrites that shared runtime.
                break;
            case ROOTFS_PROOT:
                String containerId = "container-" + UUID.randomUUID();
                profile = new RuntimeProfile(id, "rootfs_hangover_11.16", "rootfs-llvmpipe",
                    "rootfs-wined3d", "pulseaudio", profile.getResolution(), profile.getBox64Preset(),
                    profile.getEnvironment(), profile.getInputProfileId(), profile.getLaunchExecutionMode(),
                    profile.getComponentVersions(), backend, GameContainer.ROOTFS_RUNTIME_PACKAGE,
                    containerId);
                containers.save(GameContainerFactory.fromProfile(profile));
                break;
            default:
                throw new IllegalArgumentException("unsupported_runtime_backend:" + backend);
        }
        // These repositories belong to the draft store; Save publishes to the actual library.
        profiles.save(profile);
        games.save(game);
        return id;
    }

    private static String defaultGameName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        return name.trim().isEmpty() ? fileName : name;
    }
}
