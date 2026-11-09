package com.termux.ui.wine.importer;

import android.net.Uri;

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
        return createConfiguration(drafts.games(), drafts.profiles(), drafts.containers(), Uri.fromFile(parent).toString(),
            pickedFile.getName(), backend);
    }

    static String createConfiguration(GameRepository games, RuntimeProfileRepository profiles,
            GameContainerRepository containers, String rootUri, String executable,
            GameRuntimeBackendType backend) throws IOException {
        if (backend == null) throw new IllegalArgumentException("runtimeBackendRequired");
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
