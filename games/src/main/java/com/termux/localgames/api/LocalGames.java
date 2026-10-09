package com.termux.localgames.api;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.localgames.activity.GameFileManagerActivity;
import com.termux.localgames.activity.GameDetailActivity;
import com.termux.localgames.activity.GameReauthorizeActivity;
import com.termux.localgames.activity.GameRuntimeOptionsActivity;
import com.termux.localgames.activity.GameAssetsActivity;
import com.termux.localgames.activity.GameLaunchActivity;
import com.termux.localgames.activity.RuntimeBackupActivity;
import com.termux.localgames.activity.GameSessionActivity;
import com.termux.localgames.activity.LocalGamesActivity;

/** Public integration entry point for the standalone games feature. */
public final class LocalGames {

    private static volatile LocalGamesHostFactory hostFactory;

    /** Consumed once by {@code LocalGamesActivity} to auto-open the live setup console for a
     *  runtime warm-up task just enqueued by a runtime-options Save. */
    public static final String EXTRA_PENDING_SETUP_TASK_ID =
        "com.termux.localgames.extra.PENDING_SETUP_TASK_ID";
    public static final String EXTRA_PENDING_SETUP_IS_ROOTFS =
        "com.termux.localgames.extra.PENDING_SETUP_IS_ROOTFS";

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

    /** Opens the Library tab and, once loaded, auto-shows the live console for a runtime
     *  warm-up task just enqueued by a runtime-options Save -- reuses the existing single
     *  {@code LocalGamesActivity} instance on the back stack instead of stacking a new one. */
    @NonNull
    public static Intent createLibraryIntentShowingSetupConsole(@NonNull Context context,
                                                                  @Nullable String taskId,
                                                                  boolean rootfs) {
        Intent intent = createLaunchIntent(context)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (taskId != null) {
            intent.putExtra(EXTRA_PENDING_SETUP_TASK_ID, taskId)
                .putExtra(EXTRA_PENDING_SETUP_IS_ROOTFS, rootfs);
        }
        return intent;
    }

    /** Opens the component view scoped to one game's selected runtime backend. */
    @NonNull
    public static Intent createComponentsIntent(@NonNull Context context, @NonNull String gameId) {
        return new Intent(context, LocalGamesActivity.class)
            .putExtra(LocalGamesActivity.EXTRA_COMPONENT_GAME_ID, gameId);
    }

    /** Opens the file manager used to browse private games and the mounted shared storage tree,
     *  and (via long-press on a file) import a new game -- see GameFileManagerActivity. */
    @NonNull
    public static Intent createFileManagerIntent(@NonNull Context context) {
        return new Intent(context, GameFileManagerActivity.class);
    }

    /** Re-grants a lost SAF permission for an already-imported game; see GameReauthorizeActivity. */
    @NonNull
    public static Intent createReauthorizeIntent(@NonNull Context context,
                                                  @NonNull String treeUri) {
        return new Intent(context, GameReauthorizeActivity.class)
            .putExtra(GameReauthorizeActivity.EXTRA_TREE_URI, treeUri);
    }

    /** Opens a persisted game by stable id; the Activity reloads current repository state. */
    @NonNull
    public static Intent createGameDetailIntent(@NonNull Context context,
                                                 @NonNull String gameId) {
        return new Intent(context, GameDetailActivity.class)
            .putExtra(GameDetailActivity.EXTRA_GAME_ID, gameId);
    }

    /** Opens the per-game launch parameter editor. */
    @NonNull
    public static Intent createRuntimeOptionsIntent(@NonNull Context context,
                                                    @NonNull String gameId) {
        return new Intent(context, GameRuntimeOptionsActivity.class)
            .putExtra(GameRuntimeOptionsActivity.EXTRA_GAME_ID, gameId);
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
