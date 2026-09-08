package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

import com.termux.localgames.activity.GameImportActivity;
import com.termux.localgames.activity.GameFileManagerActivity;
import com.termux.localgames.activity.GameDetailActivity;
import com.termux.localgames.activity.GameRuntimeProfileActivity;
import com.termux.localgames.activity.GameAssetsActivity;
import com.termux.localgames.activity.GameLaunchActivity;
import com.termux.localgames.activity.RuntimeBackupActivity;
import com.termux.localgames.activity.GameSessionActivity;
import com.termux.localgames.activity.LocalGamesActivity;

/** Public integration entry point for the standalone games feature. */
public final class LocalGames {

    private static volatile LocalGamesHostFactory hostFactory;

    private LocalGames() {
    }

    /**
     * Installs the app-owned runtime bridge. This method performs no I/O and is
     * safe to call from {@code Application.onCreate()} after every process start.
     */
    public static void install(@NonNull LocalGamesHostFactory factory) {
        hostFactory = factory;
    }

    /** Creates an explicit intent for the Activity owned by this feature module. */
    @NonNull
    public static Intent createLaunchIntent(@NonNull Context context) {
        return new Intent(context, LocalGamesActivity.class);
    }

    /** Opens the runtime component catalog directly. */
    @NonNull
    public static Intent createComponentsIntent(@NonNull Context context) {
        return new Intent(context, LocalGamesActivity.class)
            .putExtra(LocalGamesActivity.EXTRA_OPEN_COMPONENTS, true);
    }

    /** Creates an explicit intent for the SAF game-directory import flow. */
    @NonNull
    public static Intent createImportIntent(@NonNull Context context) {
        return new Intent(context, GameImportActivity.class);
    }

    /** Opens the file manager used to browse private games and authorized folders. */
    @NonNull
    public static Intent createFileManagerIntent(@NonNull Context context) {
        return new Intent(context, GameFileManagerActivity.class);
    }

    /** Opens the import flow to restore access to a previously imported tree URI. */
    @NonNull
    public static Intent createReauthorizeIntent(@NonNull Context context,
                                                  @NonNull String treeUri) {
        return createImportIntent(context).putExtra(GameImportActivity.EXTRA_TREE_URI, treeUri);
    }

    /** Opens a persisted game by stable id; the Activity reloads current repository state. */
    @NonNull
    public static Intent createGameDetailIntent(@NonNull Context context,
                                                 @NonNull String gameId) {
        return new Intent(context, GameDetailActivity.class)
            .putExtra(GameDetailActivity.EXTRA_GAME_ID, gameId);
    }

    /** Opens the game-owned runtime profile editor and launch preflight. */
    @NonNull
    public static Intent createRuntimeProfileIntent(@NonNull Context context,
                                                     @NonNull String gameId) {
        return new Intent(context, GameRuntimeProfileActivity.class)
            .putExtra(GameRuntimeProfileActivity.EXTRA_GAME_ID, gameId);
    }

    /** Opens read-only private asset inventory for one game. */
    @NonNull
    public static Intent createGameAssetsIntent(@NonNull Context context,
                                                 @NonNull String gameId) {
        return new Intent(context, GameAssetsActivity.class)
            .putExtra(GameAssetsActivity.EXTRA_GAME_ID, gameId);
    }

    /** Opens offline export and verified restore for complete GLIBC or RootFS runtimes. */
    @NonNull
    public static Intent createRuntimeBackupIntent(@NonNull Context context) {
        return new Intent(context, RuntimeBackupActivity.class);
    }

    /** Opens the persistent task status page for one game. */
    @NonNull
    public static Intent createGameLaunchIntent(@NonNull Context context,
                                                 @NonNull String gameId) {
        return new Intent(context, GameLaunchActivity.class)
            .putExtra(GameLaunchActivity.EXTRA_GAME_ID, gameId);
    }

    /** Opens the X11 session surface for an existing persistent launch task. */
    @NonNull
    public static Intent createGameSessionIntent(@NonNull Context context,
                                                  @NonNull String taskId) {
        return GameSessionActivity.createIntent(context, taskId);
    }

    /** Resolves a host using only the application context. */
    @NonNull
    public static LocalGamesHost requireHost(@NonNull Context context) {
        LocalGamesHostFactory factory = hostFactory;
        if (factory == null) {
            throw new IllegalStateException("LocalGames.install() must be called from Application.onCreate()");
        }
        return factory.create(context.getApplicationContext());
    }
}
