package com.termux.localgames.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.PopupMenu;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.localgames.R;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.RuntimeProfileRepository;
import com.termux.localgames.databinding.ActivityLocalGameFileManagerBinding;
import com.termux.localgames.importer.PeExecutableInspector;
import com.termux.localgames.importer.QuickGameImport;
import com.termux.shared.android.PermissionUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browser for private game files and the mounted shared storage tree. It never mutates file
 *  content on its own -- tapping a file shows read-only details, long-pressing one offers to
 *  import it as a game. Whole-folder import (the old "⋯" menu) is gone: a game is always a
 *  specific picked file, never an unscanned directory. */
public final class GameFileManagerActivity extends AppCompatActivity {

    private static final int REQUEST_SHARED_STORAGE_PERMISSION = 8402;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesFileManagerIo"));
    private final PeExecutableInspector peInspector = new PeExecutableInspector();

    private ActivityLocalGameFileManagerBinding binding;
    private GameRepository gameRepository;
    private RuntimeProfileRepository profileRepository;
    private File filesRoot;
    private File gameFilesRoot;
    private File sharedStorageRoot;
    private File localCurrent;
    @Nullable private Runnable pendingSharedStorageAction;
    private boolean destroyed;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLocalGameFileManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        gameRepository = new FileGameRepository(paths.getLibraryDirectory());
        profileRepository = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        filesRoot = getFilesDir();
        gameFilesRoot = paths.getImportedGamesDirectory();
        if (!gameFilesRoot.exists() && !gameFilesRoot.mkdirs()) {
            Toast.makeText(this, R.string.local_game_file_manager_empty, Toast.LENGTH_SHORT).show();
        }
        sharedStorageRoot = Environment.getExternalStorageDirectory();
        binding.localGameFileManagerToolbar.setNavigationOnClickListener(view -> navigateUp());
        GamesHelpDialog.attach(binding.localGameFileManagerToolbar,
            R.string.local_game_file_manager_title, R.string.local_game_file_manager_help);
        showHome();
    }

    @Override
    public void onBackPressed() {
        if (isHome()) super.onBackPressed();
        else navigateUp();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_SHARED_STORAGE_PERMISSION) resumePendingSharedStorageAction();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_SHARED_STORAGE_PERMISSION) resumePendingSharedStorageAction();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ioExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }

    private void navigateUp() {
        if (localCurrent == null) {
            finish();
            return;
        }
        File root = rootFor(localCurrent);
        if (root == null || localCurrent.equals(root)) {
            showHome();
            return;
        }
        File parent = localCurrent.getParentFile();
        if (parent == null || !isWithin(root, parent)) showHome();
        else showLocal(parent);
    }

    private boolean isHome() {
        return localCurrent == null;
    }

    /** Which of the three fixed roots a directory currently being browsed belongs to -- used to
     *  know where "up" from the top of a tree should land (back to Home, not past the root). */
    @Nullable
    private File rootFor(File directory) {
        if (isWithin(gameFilesRoot, directory)) return gameFilesRoot;
        if (isWithin(sharedStorageRoot, directory)) return sharedStorageRoot;
        if (isWithin(filesRoot, directory)) return filesRoot;
        return null;
    }

    private void showHome() {
        localCurrent = null;
        binding.localGameFileManagerLocation.setText(
            R.string.local_game_file_manager_internal_storage);
        clearRows();
        addTile(getString(R.string.local_game_file_manager_game_files), true,
            view -> showLocal(gameFilesRoot), null);
        addTile(getString(R.string.local_game_file_manager_private_files), true,
            view -> showLocal(filesRoot), null);
        addTile(getString(R.string.local_game_file_manager_shared_storage), true,
            view -> ensureSharedStoragePermission(() -> showLocal(sharedStorageRoot)), null);
    }

    /** Request #4: games may only be imported from the app's own private storage or, once
     *  granted, the mounted shared/external storage tree -- gated the same way
     *  LocalGamesActivity.ensureBackupStoragePermission() gates Backup/Restore's own
     *  MANAGE_EXTERNAL_STORAGE need (check -> request -> resume from the activity-result
     *  callback). */
    private void ensureSharedStoragePermission(Runnable action) {
        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                this, -1, false)) {
            action.run();
            return;
        }
        pendingSharedStorageAction = action;
        PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
            this, REQUEST_SHARED_STORAGE_PERMISSION, true);
    }

    private void resumePendingSharedStorageAction() {
        Runnable action = pendingSharedStorageAction;
        pendingSharedStorageAction = null;
        if (action == null) return;
        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                this, -1, true)) {
            action.run();
        }
    }

    private void showLocal(File directory) {
        try {
            File canonical = directory.getCanonicalFile();
            File root = rootFor(canonical);
            if (root == null || !canonical.isDirectory()) {
                showHome();
                return;
            }
            localCurrent = canonical;
            binding.localGameFileManagerLocation.setText(canonical.getPath());
            clearRows();
            File[] entries = canonical.listFiles();
            if (entries == null || entries.length == 0) {
                addEmpty(getString(R.string.local_game_file_manager_empty));
                return;
            }
            Arrays.sort(entries, Comparator.comparing(File::isFile).thenComparing(File::getName,
                String.CASE_INSENSITIVE_ORDER));
            for (File entry : entries) {
                if (!isWithin(root, entry)) continue;
                if (entry.isDirectory()) {
                    // Folders are purely navigable now -- no "⋯"/import-as-folder action. A game
                    // is always a specific file the user picked (see request #1).
                    addTile(entry.getName(), true, view -> showLocal(entry), null);
                } else {
                    addTile(entry.getName(), false, view -> showFileDetails(entry),
                        view -> showFileImportMenu(view, entry));
                }
            }
        } catch (IOException error) {
            showHome();
        }
    }

    /** Request #2: tapping a file shows read-only details instead of doing nothing. */
    private void showFileDetails(File file) {
        boolean looksExecutable = looksLikePortableExecutable(file);
        String details = getString(R.string.local_game_file_details_body,
            Formatter.formatShortFileSize(this, Math.max(0, file.length())),
            DateFormat.getDateTimeInstance().format(new Date(file.lastModified())),
            getString(looksExecutable ? R.string.local_game_file_details_looks_executable
                : R.string.local_game_file_details_not_executable));
        new MaterialAlertDialogBuilder(this)
            .setTitle(file.getName())
            .setMessage(details + "\n\n" + getString(R.string.local_game_file_details_c_drive_tip))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_game_file_manager_import_as_game,
                (dialog, which) -> quickImport(file))
            .show();
    }

    private boolean looksLikePortableExecutable(File file) {
        try (InputStream input = new FileInputStream(file)) {
            return peInspector.isPortableExecutable(input);
        } catch (IOException error) {
            return false;
        }
    }

    /** Request #2: long-pressing a file offers to import it directly (no whole-folder scan). */
    private void showFileImportMenu(View anchor, File file) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(R.string.local_game_file_manager_import_as_game);
        menu.setOnMenuItemClickListener(item -> {
            quickImport(file);
            return true;
        });
        menu.show();
    }

    /** Request #3: no confirmation screen -- import immediately and land in the game's own edit
     *  page. GameImportReadinessGate.require() is the same fail-fast check
     *  GameImportActivity.onCreate() used to run; with that Activity gone this is now the sole
     *  backstop for this entry point (LocalGamesActivity.startImportFlow() already runs it once
     *  before launching this Activity, but re-checking here guards a stale instance reached any
     *  other way, e.g. process restore). */
    private void quickImport(File file) {
        GameImportReadinessGate.require(this, ioExecutor, () -> ioExecutor.execute(() -> {
            String gameId;
            String error = null;
            try {
                gameId = QuickGameImport.importGame(gameRepository, profileRepository, file, this);
            } catch (IOException | RuntimeException importError) {
                gameId = null;
                error = importError.getMessage() == null
                    ? importError.getClass().getSimpleName() : importError.getMessage();
            }
            String importedId = gameId;
            String failure = error;
            runOnUiThread(() -> {
                if (destroyed) return;
                if (failure != null) {
                    Toast.makeText(this, getString(R.string.local_game_import_save_failed, failure),
                        Toast.LENGTH_LONG).show();
                    return;
                }
                startActivity(LocalGames.createGameDetailIntent(this, importedId));
                finish();
            });
        }));
    }

    private void clearRows() {
        binding.localGameFileManagerItems.removeAllViews();
        binding.localGameFileManagerItems.setColumnCount(gridColumns());
    }

    private void addTile(String title, boolean directory, @Nullable View.OnClickListener onTap,
                         @Nullable View.OnClickListener onLongPress) {
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.local_games_bg_file_tile);
        tile.setOnClickListener(onTap);
        tile.setEnabled(onTap != null);
        if (onLongPress != null) {
            tile.setOnLongClickListener(view -> {
                onLongPress.onClick(view);
                return true;
            });
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(8), dp(10), dp(8), dp(4));
        tile.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(directory ? R.drawable.local_games_ic_folder :
            R.drawable.local_games_ic_file);
        icon.setContentDescription(null);
        content.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(getColor(directory ? R.color.local_games_on_surface :
            R.color.local_games_on_surface_secondary));
        label.setTextSize(11);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        labelParams.topMargin = dp(5);
        content.addView(label, labelParams);

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = gridTileWidth();
        params.height = dp(112);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
        params.setMargins(dp(5), dp(5), dp(5), dp(5));
        binding.localGameFileManagerItems.addView(tile, params);
    }

    private void addEmpty(String message) {
        TextView empty = new TextView(this);
        empty.setText(message);
        empty.setTextColor(getColor(R.color.local_games_on_surface_secondary));
        empty.setPadding(dp(8), dp(24), dp(8), dp(24));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(0, gridColumns());
        binding.localGameFileManagerItems.addView(empty, params);
    }

    private int gridColumns() {
        return getResources().getConfiguration().orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 6 : 4;
    }

    private int gridTileWidth() {
        int horizontalPadding = dp(40);
        int cellMargins = dp(10);
        int available = Math.max(dp(96), getResources().getDisplayMetrics().widthPixels -
            horizontalPadding - cellMargins * gridColumns());
        return Math.max(dp(96), available / gridColumns());
    }

    private boolean isWithin(File root, File candidate) {
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalCandidate = candidate.getCanonicalFile();
            String prefix = canonicalRoot.getPath() + File.separator;
            return canonicalCandidate.equals(canonicalRoot) ||
                canonicalCandidate.getPath().startsWith(prefix);
        } catch (IOException error) {
            return false;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
