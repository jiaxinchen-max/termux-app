package com.termux.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.MenuItem;
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
import androidx.core.content.ContextCompat;

import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.data.FileGameRepository;
import com.termux.ui.data.FileRuntimeProfileRepository;
import com.termux.ui.data.GameRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.data.RuntimeProfileRepository;
import com.termux.ui.databinding.ActivityLocalGameFileManagerBinding;
import com.termux.ui.importer.PeExecutableInspector;
import com.termux.ui.importer.QuickGameImport;
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
    private static final String STATE_BROWSED_DIRECTORY = "browsed_directory";
    private static final int TILE_MARGIN_DP = 3;
    private static final int TILE_HEIGHT_DP = 80;
    private static final int TILE_ICON_DP = 36;
    private static final int ROW_MIN_HEIGHT_DP = 40;
    private static final int ROW_ICON_DP = 26;
    private static final int ROW_MARGIN_DP = 1;

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
    private FileManagerViewMode viewMode = FileManagerViewMode.GRID;
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
        viewMode = FileManagerViewMode.load(this);
        binding.localGameFileManagerToolbar.inflateMenu(R.menu.local_games_file_manager);
        GamesHelpDialog.attach(binding.localGameFileManagerToolbar,
            R.string.local_game_file_manager_title, R.string.local_game_file_manager_help);
        // attach() installed the help-only listener; replace it with one that also handles the toggle.
        binding.localGameFileManagerToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.local_games_action_view_mode) {
                toggleViewMode();
                return true;
            }
            if (item.getItemId() == R.id.local_games_action_help) {
                GamesHelpDialog.show(this, R.string.local_game_file_manager_title,
                    R.string.local_game_file_manager_help);
                return true;
            }
            return false;
        });
        updateViewModeAction();
        WindowInsetsPadding.apply(binding.getRoot(), true, false, true, true);
        File restored = restoreBrowsedDirectory(savedInstanceState);
        if (restored != null) showLocal(restored);
        else showHome();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (localCurrent != null) {
            outState.putString(STATE_BROWSED_DIRECTORY, localCurrent.getPath());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (binding == null) return;
        // Keeps localCurrent (showLocal re-canonicalises it) and pendingSharedStorageAction.
        File current = localCurrent;
        if (current != null) showLocal(current);
        else showHome();
    }

    private void toggleViewMode() {
        viewMode = viewMode.toggled();
        viewMode.save(this);
        updateViewModeAction();
        File current = localCurrent;
        if (current != null) showLocal(current);
        else showHome();
    }

    /** The toolbar action shows the mode a tap switches to. */
    private void updateViewModeAction() {
        MenuItem action = binding.localGameFileManagerToolbar.getMenu()
            .findItem(R.id.local_games_action_view_mode);
        if (action == null) return;
        boolean toList = viewMode == FileManagerViewMode.GRID;
        action.setIcon(toList ? R.drawable.local_games_ic_view_list
            : R.drawable.local_games_ic_view_grid);
        action.setTitle(toList ? R.string.local_game_file_manager_view_list
            : R.string.local_game_file_manager_view_grid);
    }

    /** Process-death safe restore of the browsed directory; null means "show Home". */
    @Nullable
    private File restoreBrowsedDirectory(@Nullable Bundle state) {
        if (state == null) return null;
        String path = state.getString(STATE_BROWSED_DIRECTORY);
        if (path == null) return null;
        File directory = new File(path);
        File root = rootFor(directory);
        if (root == null || !isWithin(root, directory) || !directory.isDirectory()) return null;
        if (isWithin(sharedStorageRoot, directory)
            && !PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                this, -1, false)) {
            return null;
        }
        return directory;
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
        addEntry(getString(R.string.local_game_file_manager_game_files), null, true,
            view -> showLocal(gameFilesRoot), null);
        addEntry(getString(R.string.local_game_file_manager_private_files), null, true,
            view -> showLocal(filesRoot), null);
        addEntry(getString(R.string.local_game_file_manager_shared_storage), null, true,
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
                    addEntry(entry.getName(), entrySubtitle(entry), true, view -> showLocal(entry),
                        null);
                } else {
                    addEntry(entry.getName(), entrySubtitle(entry), false,
                        view -> showFileDetails(entry),
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
        new FrostedDialogBuilder(this)
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

    /** Second line of a list row: size and date for a file, date for a folder. */
    private String entrySubtitle(File entry) {
        String date = DateFormat.getDateInstance(DateFormat.SHORT)
            .format(new Date(entry.lastModified()));
        if (entry.isDirectory()) return getString(R.string.local_game_file_manager_folder, date);
        return getString(R.string.local_game_file_manager_file,
            Formatter.formatShortFileSize(this, Math.max(0, entry.length())), date);
    }

    private void addEntry(String title, @Nullable String subtitle, boolean directory,
                          @Nullable View.OnClickListener onTap,
                          @Nullable View.OnClickListener onLongPress) {
        if (viewMode == FileManagerViewMode.LIST) {
            addRow(title, subtitle, directory, onTap, onLongPress);
        } else {
            addTile(title, directory, onTap, onLongPress);
        }
    }

    private void addRow(String title, @Nullable String subtitle, boolean directory,
                        @Nullable View.OnClickListener onTap,
                        @Nullable View.OnClickListener onLongPress) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(ROW_MIN_HEIGHT_DP));
        row.setPadding(dp(8), dp(3), dp(8), dp(3));
        row.setBackgroundResource(R.drawable.local_games_bg_file_tile);
        row.setOnClickListener(onTap);
        row.setEnabled(onTap != null);
        if (onLongPress != null) {
            row.setOnLongClickListener(view -> {
                onLongPress.onClick(view);
                return true;
            });
        }

        ImageView icon = new ImageView(this);
        icon.setImageResource(directory ? R.drawable.local_games_ic_folder :
            R.drawable.local_games_ic_file);
        icon.setContentDescription(null);
        row.addView(icon, new LinearLayout.LayoutParams(dp(ROW_ICON_DP), dp(ROW_ICON_DP)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textsParams = new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textsParams.setMarginStart(dp(10));
        row.addView(texts, textsParams);

        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(ContextCompat.getColor(this, directory ? R.color.local_games_on_surface :
            R.color.local_games_on_surface_secondary));
        name.setTextSize(13);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        texts.addView(name, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (subtitle != null) {
            TextView detail = new TextView(this);
            detail.setText(subtitle);
            detail.setTextColor(ContextCompat.getColor(this,
                R.color.local_games_on_surface_secondary));
            detail.setTextSize(10);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            texts.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = gridTileWidth();
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
        params.setMargins(dp(ROW_MARGIN_DP * 3), dp(ROW_MARGIN_DP), dp(ROW_MARGIN_DP * 3),
            dp(ROW_MARGIN_DP));
        binding.localGameFileManagerItems.addView(row, params);
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
        content.setPadding(dp(4), dp(6), dp(4), dp(2));
        tile.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ImageView icon = new ImageView(this);
        icon.setImageResource(directory ? R.drawable.local_games_ic_folder :
            R.drawable.local_games_ic_file);
        icon.setContentDescription(null);
        content.addView(icon, new LinearLayout.LayoutParams(dp(TILE_ICON_DP), dp(TILE_ICON_DP)));

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(ContextCompat.getColor(this, directory ? R.color.local_games_on_surface :
            R.color.local_games_on_surface_secondary));
        label.setTextSize(10);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(28));
        labelParams.topMargin = dp(2);
        content.addView(label, labelParams);

        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = gridTileWidth();
        params.height = dp(TILE_HEIGHT_DP);
        params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED);
        params.setMargins(dp(TILE_MARGIN_DP), dp(TILE_MARGIN_DP), dp(TILE_MARGIN_DP),
            dp(TILE_MARGIN_DP));
        binding.localGameFileManagerItems.addView(tile, params);
    }

    private void addEmpty(String message) {
        TextView empty = new TextView(this);
        empty.setText(message);
        empty.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface_secondary));
        empty.setPadding(dp(8), dp(16), dp(8), dp(16));
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = ViewGroup.LayoutParams.MATCH_PARENT;
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(0, gridColumns());
        binding.localGameFileManagerItems.addView(empty, params);
    }

    private int gridColumns() {
        float density = getResources().getDisplayMetrics().density;
        int widthDp = Math.round(availableGridWidthPx() / density);
        return viewMode == FileManagerViewMode.LIST
            ? FileManagerGridColumns.listColumnsFor(widthDp, isLandscape())
            : FileManagerGridColumns.columnsFor(widthDp, isLandscape());
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Width of the grid content area. Uses the window's own width (screenWidthDp tracks
     *  split-screen/freeform size, unlike the display metrics) minus the root and grid padding. */
    private int availableGridWidthPx() {
        float density = getResources().getDisplayMetrics().density;
        int windowPx = Math.round(getResources().getConfiguration().screenWidthDp * density);
        if (windowPx <= 0) windowPx = getResources().getDisplayMetrics().widthPixels;
        View root = binding.getRoot();
        View grid = binding.localGameFileManagerItems;
        int padding = root.getPaddingLeft() + root.getPaddingRight()
            + grid.getPaddingLeft() + grid.getPaddingRight();
        return Math.max(0, windowPx - padding);
    }

    private int gridTileWidth() {
        int margin = viewMode == FileManagerViewMode.LIST ? dp(ROW_MARGIN_DP * 3)
            : dp(TILE_MARGIN_DP);
        return FileManagerGridColumns.tileWidthPx(availableGridWidthPx(), gridColumns(), margin);
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
