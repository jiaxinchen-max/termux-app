package com.termux.ui.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
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
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;

import com.termux.shared.android.PermissionUtils;
import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.files.BrowserFileOperations;
import com.termux.ui.files.BrowserDocumentsProvider;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.databinding.ActivityLocalGameFileManagerBinding;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.importer.PeExecutableInspector;
import com.termux.ui.wine.importer.QuickGameImport;

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

/** Browser with explicit file operations and temporary read-only system-open grants. */
public final class GameFileManagerActivity extends AppUiActivity {
    public static final String EXTRA_IMPORT_BACKEND = "com.termux.ui.extra.IMPORT_BACKEND";
    @Nullable private GameRuntimeBackendType importBackend;

    private static final int REQUEST_SHARED_STORAGE_PERMISSION = 8402;
    private static final String STATE_BROWSED_DIRECTORY = "browsed_directory";
    private static final int TILE_MARGIN_DP = 1;
    private static final int TILE_HEIGHT_DP = 80;
    private static final int TILE_ICON_DP = 36;
    private static final int ROW_MIN_HEIGHT_DP = 40;
    private static final int ROW_ICON_DP = 26;
    private static final int ROW_MARGIN_DP = 1;
    private static final int DIRECTORY_CHANGE_EVENTS = FileObserver.CREATE | FileObserver.DELETE
        | FileObserver.MOVED_FROM | FileObserver.MOVED_TO | FileObserver.DELETE_SELF
        | FileObserver.MOVE_SELF;
    private static final long DIRECTORY_REFRESH_DELAY_MS = 150L;

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesFileManagerIo"));
    private final PeExecutableInspector peInspector = new PeExecutableInspector();
    private final Handler directoryHandler = new Handler(Looper.getMainLooper());
    private final Runnable directoryRefresh = this::refreshDirectory;

    private ActivityLocalGameFileManagerBinding binding;
    private UiNavigation navigation;
    private CollectionToolbar collectionToolbar;
    private File filesRoot;
    private File gameFilesRoot;
    private File sharedStorageRoot;
    private File localCurrent;
    private FileManagerViewMode viewMode = FileManagerViewMode.GRID;
    @Nullable private Runnable pendingSharedStorageAction;
    @Nullable private FileObserver directoryObserver;
    @Nullable private File watchedDirectory;
    private boolean resumed;
    private boolean destroyed;
    private boolean importing;
    private boolean operating;
    private boolean showHidden;
    private BrowserFileOperations fileOperations;
    @Nullable private File clipboardSource;
    private boolean clipboardCut;
    @Nullable private PopupMenu activeFileMenu;
    private static final int OPEN = 101, COPY = 102, CUT = 103, DELETE = 104, PASTE = 105, IMPORT = 106;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readImportBackend(getIntent());
        binding = ActivityLocalGameFileManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        filesRoot = getFilesDir();
        gameFilesRoot = paths.getImportedGamesDirectory();
        if (!gameFilesRoot.exists() && !gameFilesRoot.mkdirs()) {
            Toast.makeText(this, R.string.local_game_file_manager_empty, Toast.LENGTH_SHORT).show();
        }
        sharedStorageRoot = Environment.getExternalStorageDirectory();
        fileOperations = new BrowserFileOperations(filesRoot, gameFilesRoot, sharedStorageRoot);
        showHidden = getSharedPreferences("local_games_settings", MODE_PRIVATE)
            .getBoolean("file_manager_show_hidden", false);
        if (savedInstanceState != null) {
            String source = savedInstanceState.getString("clipboard_source");
            if (source != null) clipboardSource = new File(source);
            clipboardCut = savedInstanceState.getBoolean("clipboard_cut");
        }
        collectionToolbar = new CollectionToolbar(this, binding.localGameFileManagerToolbar,
            "file_manager", R.string.local_game_file_manager_title,
            R.string.local_game_file_manager_help, this::refreshDirectory, () -> {
                viewMode = collectionToolbar.getMode();
                File current = localCurrent;
                if (current != null) showLocal(current); else showHome();
            });
        viewMode = collectionToolbar.getMode();
        configureFileActions();
        View.OnLongClickListener blankMenu = view -> {
            if (localCurrent == null || operating || importing) return false;
            return showFileMenu(view, localCurrent, true);
        };
        binding.localGameFileManagerItems.setOnLongClickListener(blankMenu);
        binding.localGameFileManagerScroll.setOnLongClickListener(blankMenu);
        navigation = UiNavigation.install(this, R.id.local_games_navigation_file_manager, null);
        File restored = restoreBrowsedDirectory(savedInstanceState);
        if (restored != null) showLocal(restored);
        else showHome();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        readImportBackend(intent);
    }

    private void readImportBackend(Intent intent) {
        String value = intent.getStringExtra(EXTRA_IMPORT_BACKEND);
        importBackend = value == null ? null : GameRuntimeBackendType.fromStorageValue(value);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        if (localCurrent != null) refreshDirectory();
    }

    @Override
    protected void onPause() {
        resumed = false;
        stopWatchingDirectory();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (clipboardSource != null) {
            outState.putString("clipboard_source", clipboardSource.getPath());
            outState.putBoolean("clipboard_cut", clipboardCut);
        }
        if (localCurrent != null) {
            outState.putString(STATE_BROWSED_DIRECTORY, localCurrent.getPath());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        navigation.updateConfiguration();
        if (binding == null) return;
        // Keeps localCurrent (showLocal re-canonicalises it) and pendingSharedStorageAction.
        File current = localCurrent;
        if (current != null) showLocal(current);
        else showHome();
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
        if (activeFileMenu != null) activeFileMenu.dismiss();
        stopWatchingDirectory();
        directoryHandler.removeCallbacksAndMessages(null);
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
        stopWatchingDirectory();
        localCurrent = null;
        updateFileActions();
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
            updateFileActions();
            watchDirectory(canonical);
            binding.localGameFileManagerLocation.setText(canonical.getPath());
            clearRows();
            File[] entries = canonical.listFiles();
            if (entries == null) {
                addEmpty(getString(R.string.local_game_file_manager_unreadable));
                return;
            }
            if (entries.length == 0) {
                addEmpty(getString(R.string.local_game_file_manager_empty));
                return;
            }
            Arrays.sort(entries, Comparator.comparing(File::isFile).thenComparing(File::getName,
                String.CASE_INSENSITIVE_ORDER));
            int visible = 0;
            for (File entry : entries) {
                if (!showHidden && entry.getName().startsWith(".")) continue;
                if (!isWithin(root, entry)) continue;
                visible++;
                if (entry.isDirectory()) {
                    addEntry(entry.getName(), entrySubtitle(entry), true, view -> showLocal(entry),
                        view -> showFileMenu(view, entry, false));
                } else {
                    addEntry(entry.getName(), entrySubtitle(entry), false,
                        view -> showFileDetails(entry),
                        view -> showFileMenu(view, entry, false));
                }
            }
            if (visible == 0) addEmpty(getString(R.string.local_game_file_manager_empty));
        } catch (IOException error) {
            showHome();
        }
    }

    private void refreshDirectory() {
        if (destroyed || operating || binding == null || localCurrent == null) return;
        int scrollY = binding.localGameFileManagerScroll.getScrollY();
        File directory = localCurrent;
        showLocal(directory);
        binding.localGameFileManagerScroll.post(() -> {
            if (!destroyed && binding != null && directory.equals(localCurrent)) {
                binding.localGameFileManagerScroll.scrollTo(0, scrollY);
            }
        });
    }

    private void watchDirectory(File directory) {
        if (directoryObserver != null && directory.equals(watchedDirectory)) return;
        stopWatchingDirectory();
        if (!resumed || operating) return;
        watchedDirectory = directory;
        directoryObserver = new FileObserver(directory.getPath(), DIRECTORY_CHANGE_EVENTS) {
            @Override
            public void onEvent(int event, @Nullable String path) {
                // FileObserver calls from its own thread. Ignore callbacks from a previous
                // directory and coalesce bursts from transfers into one UI update.
                directoryHandler.post(() -> {
                    if (destroyed || !resumed || directoryObserver != this) return;
                    directoryHandler.removeCallbacks(directoryRefresh);
                    directoryHandler.postDelayed(directoryRefresh, DIRECTORY_REFRESH_DELAY_MS);
                });
            }
        };
        directoryObserver.startWatching();
    }

    private void stopWatchingDirectory() {
        directoryHandler.removeCallbacks(directoryRefresh);
        if (directoryObserver != null) {
            directoryObserver.stopWatching();
            directoryObserver = null;
        }
        watchedDirectory = null;
    }

    /** Request #2: tapping a file shows read-only details instead of doing nothing. */
    private void showFileDetails(File file) {
        boolean looksExecutable = looksLikePortableExecutable(file);
        String details = getString(R.string.local_game_file_details_body,
            Formatter.formatShortFileSize(this, Math.max(0, file.length())),
            DateFormat.getDateTimeInstance().format(new Date(file.lastModified())),
            getString(looksExecutable ? R.string.local_game_file_details_looks_executable
                : R.string.local_game_file_details_not_executable));
        GameRuntimeBackendType backend = selectedImportBackend();
        new FrostedDialogBuilder(this)
            .setTitle(file.getName())
            .setMessage(details + "\n\n" + getString(R.string.local_game_file_details_c_drive_tip))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(importLabel(backend),
                (dialog, which) -> importConfiguration(file, backend))
            .show();
    }

    private boolean looksLikePortableExecutable(File file) {
        try (InputStream input = new FileInputStream(file)) {
            return peInspector.isPortableExecutable(input);
        } catch (IOException error) {
            return false;
        }
    }

    private void configureFileActions() {
        binding.localGameFileManagerToolbar.inflateMenu(R.menu.local_game_browser_actions);
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_show_hidden)
            .setOnMenuItemClickListener(item -> {
                showHidden = !showHidden;
                getSharedPreferences("local_games_settings", MODE_PRIVATE).edit()
                    .putBoolean("file_manager_show_hidden", showHidden).apply();
                updateFileActions();
                refreshDirectory();
                return true;
            });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_paste)
            .setOnMenuItemClickListener(item -> { pasteIntoCurrent(); return true; });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_cancel_clipboard)
            .setOnMenuItemClickListener(item -> {
                clipboardSource = null; updateFileActions(); return true;
            });
        updateFileActions();
    }

    private void updateFileActions() {
        if (binding == null) return;
        android.view.Menu menu = binding.localGameFileManagerToolbar.getMenu();
        if (menu.findItem(R.id.browser_paste) == null) return;
        boolean pending = clipboardSource != null;
        boolean ready = !operating && !importing;
        menu.findItem(R.id.browser_paste).setVisible(pending).setEnabled(ready && localCurrent != null)
            .setTitle(pending ? getString(R.string.browser_paste_named, clipboardSource.getName()) : getString(R.string.browser_paste));
        menu.findItem(R.id.browser_cancel_clipboard).setVisible(pending).setEnabled(ready);
        menu.findItem(R.id.browser_show_hidden).setChecked(showHidden).setEnabled(ready);
    }

    private boolean showFileMenu(View anchor, File target, boolean blank) {
        if (operating || importing || destroyed) return false;
        boolean allowed = fileOperations.canOperate(target);
        boolean paste = blank && clipboardSource != null;
        if (!allowed && !paste) return false;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(target.getName()).setEnabled(false);
        if (allowed) {
            menu.getMenu().add(0, OPEN, 1, R.string.browser_open);
            menu.getMenu().add(0, COPY, 2, R.string.browser_copy);
            menu.getMenu().add(0, CUT, 3, R.string.browser_cut);
            menu.getMenu().add(0, DELETE, 4, R.string.browser_delete);
            if (!target.isDirectory()) menu.getMenu().add(0, IMPORT, 5, importLabel(selectedImportBackend()));
        }
        if (paste) menu.getMenu().add(0, PASTE, 6, R.string.browser_paste);
        menu.setOnMenuItemClickListener(item -> {
            if (operating || importing) return true;
            switch (item.getItemId()) {
                case OPEN: openExternally(target); break;
                case COPY: case CUT:
                    clipboardSource = target; clipboardCut = item.getItemId() == CUT;
                    updateFileActions();
                    Toast.makeText(this, getString(clipboardCut ? R.string.browser_cut_pending : R.string.browser_copy_pending,
                        target.getName()), Toast.LENGTH_SHORT).show();
                    break;
                case DELETE: confirmDelete(target); break;
                case PASTE: pasteIntoCurrent(); break;
                case IMPORT: importConfiguration(target, selectedImportBackend()); break;
            }
            return true;
        });
        if (activeFileMenu != null) activeFileMenu.dismiss();
        activeFileMenu = menu;
        menu.setOnDismissListener(dismissed -> { if (activeFileMenu == dismissed) activeFileMenu = null; });
        menu.show();
        return true;
    }

    private void openExternally(File target) {
        try {
            startActivity(BrowserDocumentsProvider.openIntent(this, target));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.browser_no_open_app, Toast.LENGTH_LONG).show();
        } catch (IOException | SecurityException e) {
            showOperationFailure(e);
        }
    }

    private void confirmDelete(File target) {
        new FrostedDialogBuilder(this).setTitle(R.string.browser_delete)
            .setMessage(getString(R.string.browser_delete_confirm, target.getName()))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.browser_delete, (dialog, which) -> runFileOperation(() -> {
                fileOperations.delete(target);
                return target;
            }, () -> {
                if (clipboardSource != null && BrowserFileOperations.contains(target.getAbsoluteFile(), clipboardSource.getAbsoluteFile())) {
                    clipboardSource = null;
                }
            })).show();
    }

    private void pasteIntoCurrent() {
        if (clipboardSource == null || localCurrent == null || operating || importing) return;
        File source = clipboardSource, destination = localCurrent;
        boolean cut = clipboardCut;
        runFileOperation(() -> fileOperations.paste(source, destination, cut), () -> {
            if (cut && source.equals(clipboardSource)) clipboardSource = null;
        });
    }

    private interface FileOperation { File run() throws IOException; }

    private void runFileOperation(FileOperation operation, Runnable success) {
        if (operating || importing || destroyed) return;
        operating = true;
        stopWatchingDirectory();
        updateFileActions();
        binding.localGameFileManagerProgress.setVisibility(View.VISIBLE);
        ioExecutor.execute(() -> {
            Exception failure = null;
            try { operation.run(); } catch (IOException | RuntimeException e) { failure = e; }
            Exception result = failure;
            runOnUiThread(() -> {
                operating = false;
                if (destroyed || binding == null) return;
                binding.localGameFileManagerProgress.setVisibility(View.GONE);
                if (result == null) success.run(); else showOperationFailure(result);
                File current = localCurrent;
                // Deleting the current directory returns to the nearest accessible parent.
                while (current != null && rootFor(current) != null && !current.isDirectory()) current = current.getParentFile();
                if (current != null && rootFor(current) != null) showLocal(current); else showHome();
                updateFileActions();
            });
        });
    }

    private void showOperationFailure(Exception failure) {
        String detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        int message = detail.startsWith("destination_exists") ? R.string.browser_conflict
            : detail.startsWith("destination_inside_source") ? R.string.browser_inside_source
            : detail.startsWith("protected_root") ? R.string.browser_protected_root
            : detail.startsWith("move_source_delete_failed") ? R.string.browser_move_partial
            : detail.startsWith("delete_failed_may_be_partial") ? R.string.browser_delete_partial
            : R.string.browser_operation_failed;
        new FrostedDialogBuilder(this).setTitle(R.string.browser_operation_failed)
            .setMessage(getString(message) + "\n" + detail).setPositiveButton(android.R.string.ok, null).show();
    }

    private GameRuntimeBackendType selectedImportBackend() {
        return importBackend != null ? importBackend
            : CollectionToolbar.isProotSelected(this, "wine")
                ? GameRuntimeBackendType.ROOTFS_PROOT : GameRuntimeBackendType.GLIBC_TERMUX_BOX;
    }

    private String importLabel(GameRuntimeBackendType backend) {
        return getString(backend == GameRuntimeBackendType.ROOTFS_PROOT
            ? R.string.local_game_import_proot_exe : R.string.local_game_import_native_exe);
    }

    private void importConfiguration(File file, GameRuntimeBackendType backend) {
        if (destroyed || importing || operating) return;
        importing = true;
        updateFileActions();
        ioExecutor.execute(() -> {
            String gameId;
            String error = null;
            try {
                gameId = QuickGameImport.importGame(getFilesDir(), file, backend);
            } catch (IOException | RuntimeException importError) {
                gameId = null;
                error = importError.getMessage() == null
                    ? importError.getClass().getSimpleName() : importError.getMessage();
            }
            String importedId = gameId;
            String failure = error;
            runOnUiThread(() -> {
                importing = false;
                if (destroyed) return;
                updateFileActions();
                if (failure != null) {
                    Toast.makeText(this, getString(R.string.local_game_import_save_failed, failure),
                        Toast.LENGTH_LONG).show();
                    return;
                }
                startActivity(LocalGames.createGameDetailIntent(this, importedId));
                finish();
            });
        });
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
