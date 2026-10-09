package com.termux.localgames.importer;

import android.content.Context;
import android.net.Uri;

import com.termux.localgames.api.RuntimeWarmup;
import com.termux.localgames.data.GameRepository;
import com.termux.localgames.data.RuntimeProfileRepository;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.UUID;

/** Saves a game record directly from one user-picked file -- no folder scan, no candidate list.
 *  The picked file's own parent directory becomes both the game's root and its default working
 *  directory ("."), matching how GameDetailActivity's workingDirectory field is already
 *  interpreted (relative to rootUri). Mirrors the save step GameImportActivity.confirmImport()
 *  used to perform, minus the scan/ranking/UI-state parts that no longer apply once the user has
 *  already chosen the exact file themselves. */
public final class QuickGameImport {

    private QuickGameImport() {}

    public static String importGame(GameRepository gameRepository,
            RuntimeProfileRepository profileRepository, File pickedFile, Context context)
            throws IOException {
        File parent = pickedFile.getParentFile();
        if (parent == null) throw new IOException("game_root_missing");
        String rootUri = Uri.fromFile(parent).toString();
        String executable = pickedFile.getName();
        String workingDirectory = ".";
        String name = defaultGameName(executable);

        String gameId = existingGameId(gameRepository, rootUri, executable);
        if (gameId == null) gameId = UUID.randomUUID().toString();
        gameRepository.save(new Game(gameId, name, rootUri, executable, workingDirectory,
            Collections.emptyList(), "", 0));
        if (!profileRepository.find(gameId).isPresent()) {
            profileRepository.save(RuntimeProfilePresets.create(gameId,
                RuntimeProfilePreset.RECOMMENDED));
        }
        String finalGameId = gameId;
        profileRepository.find(gameId).ifPresent(profile -> RuntimeWarmup.warm(context, profile));
        return finalGameId;
    }

    private static String existingGameId(GameRepository gameRepository, String rootUri,
                                         String executable) throws IOException {
        for (Game game : gameRepository.list()) {
            if (game.getRootUri().equals(rootUri) && game.getExecutable().equals(executable)) {
                return game.getId();
            }
        }
        return null;
    }

    private static String defaultGameName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String name = dot > 0 ? fileName.substring(0, dot) : fileName;
        return name.trim().isEmpty() ? fileName : name;
    }
}
