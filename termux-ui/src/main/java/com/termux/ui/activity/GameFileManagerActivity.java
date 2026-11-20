package com.termux.ui.activity;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.content.ContextCompat;

import com.termux.shared.android.PermissionUtils;
import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.files.BrowserFileOperations;
import com.termux.ui.files.BrowserBatchOperations;
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
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/** Browser with explicit file operations and temporary read-only system-open grants. */
public final class GameFileManagerActivity extends AppUiActivity {
    public static final String EXTRA_IMPORT_BACKEND = "com.termux.ui.extra.IMPORT_BACKEND";
    public static final String EXTRA_ENTRY_GAME_ID = "com.termux.ui.extra.ENTRY_GAME_ID";
    @Nullable private String entryGameId;
    @Nullable private GameRuntimeBackendType importBackend;

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

    private ExecutorService ioExecutor;
    private BrowserBatchViewModel batchModel;
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
    @Nullable private FileObserver directoryObserver;
    @Nullable private File watchedDirectory;
    private boolean resumed;
    private boolean destroyed;
    private boolean importing;
    private boolean operating;
    private boolean showHidden;
    private BrowserFileOperations fileOperations;
    private final List<File> clipboardSources = new ArrayList<>();
    private final Set<String> blockedClipboard = new LinkedHashSet<>();
    private boolean clipboardCut;
    private boolean selecting;
    private final Set<String> selectedPaths = new LinkedHashSet<>();
    private final Map<String, File> visibleEntries = new LinkedHashMap<>();
    private final Map<String, View> entryViews = new LinkedHashMap<>();
    private final Map<String, android.widget.CheckBox> entryChecks = new LinkedHashMap<>();
    @Nullable private PopupMenu activeFileMenu;
    @Nullable private AlertDialog activeFileDetails;
    private long inspectionGeneration;
    private static final int OPEN = 101, COPY = 102, CUT = 103, DELETE = 104, IMPORT = 106, SELECT = 107;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        readImportBackend(getIntent());
        binding = ActivityLocalGameFileManagerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        batchModel = new androidx.lifecycle.ViewModelProvider(this,
            new androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory(getApplication()))
            .get(BrowserBatchViewModel.class);
        ioExecutor = batchModel.executor;
        operating = batchModel.isBusy();
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
            ArrayList<String> sources = savedInstanceState.getStringArrayList("clipboard_sources");
            if (sources != null) for (String source : sources) {
                File file = new File(source);
                if (fileOperations.canOperate(file)) clipboardSources.add(file);
            }
            ArrayList<String> blocked = savedInstanceState.getStringArrayList("clipboard_blocked");
            if (blocked != null) blockedClipboard.addAll(blocked);
            clipboardCut = savedInstanceState.getBoolean("clipboard_cut");
            selecting = savedInstanceState.getBoolean("selecting");
            ArrayList<String> selected = savedInstanceState.getStringArrayList("selected_paths");
            if (selected != null) selectedPaths.addAll(selected);
        }
        collectionToolbar = new CollectionToolbar(this, binding.localGameFileManagerToolbar,
            "file_manager", R.string.local_game_file_manager_title,
            R.string.local_game_file_manager_help, this::refreshDirectory, () -> {
                viewMode = collectionToolbar.getMode();
                File current = localCurrent;
                if (current != null) showLocal(current); else showHome();
            });
        viewMode = collectionToolbar.getMode();
        binding.localGameFileManagerToolbar.setNavigationOnClickListener(view -> navigateUp());
        binding.localGameFileManagerToolbar.setNavigationIconTint(
            ContextCompat.getColor(this, R.color.local_games_on_surface));
        binding.localGameFileManagerToolbar.setNavigationContentDescription(
            androidx.appcompat.R.string.abc_action_bar_up_description);
        configureFileActions();
        navigation = UiNavigation.install(this, R.id.local_games_navigation_file_manager, null);
        File restored = restoreBrowsedDirectory(savedInstanceState);
        if (restored != null) showLocal(restored);
        else showHome();
        batchModel.state().observe(this, this::renderBatchState);
        String interrupted = batchModel.takeInterrupted();
        if (interrupted != null) {
            clipboardSources.clear(); blockedClipboard.clear();
            updateFileActions();
            directoryHandler.post(() -> {
                if (!destroyed) showBatchMessage(getString(R.string.browser_batch_interrupted) + "\n\n" + interrupted);
            });
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        dismissFileInspection();
        setIntent(intent);
        readImportBackend(intent);
    }

    private void readImportBackend(Intent intent) {
        entryGameId = intent.getStringExtra(EXTRA_ENTRY_GAME_ID);
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
        dismissFileInspection();
        stopWatchingDirectory();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        ArrayList<String> sources = new ArrayList<>();
        for (File source : clipboardSources) sources.add(source.getPath());
        outState.putStringArrayList("clipboard_sources", sources);
        outState.putStringArrayList("clipboard_blocked", new ArrayList<>(blockedClipboard));
        outState.putBoolean("clipboard_cut", clipboardCut);
        outState.putBoolean("selecting", selecting);
        outState.putStringArrayList("selected_paths", new ArrayList<>(selectedPaths));
        if (localCurrent != null) {
            outState.putString(STATE_BROWSED_DIRECTORY, localCurrent.getPath());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        navigation.updateConfiguration();
        if (binding == null) return;
        // Keeps localCurrent (showLocal re-canonicalises it) and the clipboard.
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
        if (selecting) exitSelection();
        else if (isHome()) super.onBackPressed();
        else navigateUp();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        dismissFileInspection();
        stopWatchingDirectory();
        directoryHandler.removeCallbacksAndMessages(null);
        // The ViewModel owns its executor and any confirmed batch across recreation/closure.
        binding = null;
        super.onDestroy();
    }

    private void navigateUp() {
        if (selecting) { exitSelection(); return; }
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
        dismissFileInspection();
        stopWatchingDirectory();
        localCurrent = null;
        selecting = false;
        selectedPaths.clear();
        updateFileActions();
        binding.localGameFileManagerLocation.setText(
            R.string.local_game_file_manager_internal_storage);
        clearRows();
        addEntry(getString(R.string.local_game_file_manager_game_files), null, true,
            view -> showLocal(gameFilesRoot), null);
        addEntry(getString(R.string.local_game_file_manager_private_files), null, true,
            view -> showLocal(filesRoot), null);
        addEntry(getString(R.string.local_game_file_manager_shared_storage), null, true,
            view -> showLocal(sharedStorageRoot), null);
    }

    private void ensureSharedStoragePermission(Runnable action) {
        com.termux.ui.platform.ExternalStorageAccess.ensure(this, action);
    }

    private void showLocal(File directory) {
        if (isWithin(sharedStorageRoot, directory)) {
            ensureSharedStoragePermission(() -> showLocalReady(directory));
        } else showLocalReady(directory);
    }

    private void showLocalReady(File directory) {
        try {
            File canonical = directory.getCanonicalFile();
            File root = rootFor(canonical);
            if (root == null || !canonical.isDirectory()) {
                showHome();
                return;
            }
            if (localCurrent != null && !localCurrent.equals(canonical)) {
                dismissFileInspection();
                selecting = false;
                selectedPaths.clear();
            }
            localCurrent = canonical;
            updateFileActions();
            watchDirectory(canonical);
            binding.localGameFileManagerLocation.setText(canonical.getPath());
            clearRows();
            File[] entries = canonical.listFiles();
            if (entries == null) {
                addEmpty(getString(R.string.local_game_file_manager_unreadable));
                reconcileSelection();
                return;
            }
            if (entries.length == 0) {
                addEmpty(getString(R.string.local_game_file_manager_empty));
                reconcileSelection();
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
                    addLocalEntry(entry, true);
                } else {
                    addLocalEntry(entry, false);
                }
            }
            if (visible == 0) addEmpty(getString(R.string.local_game_file_manager_empty));
            reconcileSelection();
        } catch (IOException error) {
            showHome();
        }
    }

    private void refreshDirectory() {
        if (destroyed || operating || binding == null || localCurrent == null) return;
        int scrollY = binding.localGameFileManagerScroll.getScrollY();
        File directory = localCurrent;
        if (isWithin(sharedStorageRoot, directory)
                && !com.termux.ui.platform.ExternalStorageAccess.hasPermission(this)) {
            showHome();
            return;
        }
        showLocalReady(directory);
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

    /** Ordinary files have read-only details; import appears only after content inspection. */
    private void showFileDetails(File file) {
        if (destroyed || operating || importing) return;
        dismissFileInspection();
        String details = getString(R.string.local_game_file_details_body,
            Formatter.formatShortFileSize(this, Math.max(0, file.length())),
            DateFormat.getDateTimeInstance().format(new Date(file.lastModified())));
        GameRuntimeBackendType backend = selectedImportBackend();
        AlertDialog dialog = new FrostedDialogBuilder(this)
            .setTitle(file.getName())
            .setMessage(details)
            .setNegativeButton(android.R.string.ok, null)
            .setPositiveButton(importLabel(backend),
                (closed, which) -> importConfiguration(file, backend))
            .create();
        activeFileDetails = dialog;
        dialog.setOnDismissListener(closed -> {
            if (activeFileDetails == dialog) {
                activeFileDetails = null;
                inspectionGeneration++;
            }
        });
        showBrowserDialog(dialog);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setVisibility(View.GONE);
        inspectWindowsProgram(file, executable -> {
            if (!executable || activeFileDetails != dialog || !dialog.isShowing()) return;
            dialog.setMessage(details + "\n\n" + getString(R.string.local_game_file_details_c_drive_tip));
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setText(android.R.string.cancel);
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setVisibility(View.VISIBLE);
        });
    }

    private boolean isWindowsProgram(File file) {
        try {
            File root = rootFor(file);
            if (root == null || !isWithin(root, file) || !file.isFile()) return false;
            try (InputStream input = new FileInputStream(file)) {
                return peInspector.isWindowsProgram(input);
            }
        } catch (IOException | SecurityException error) {
            return false;
        }
    }

    private void inspectWindowsProgram(File file, java.util.function.Consumer<Boolean> result) {
        long generation = inspectionGeneration;
        ioExecutor.execute(() -> {
            boolean executable = isWindowsProgram(file);
            runOnUiThread(() -> {
                if (!destroyed && !operating && !importing && generation == inspectionGeneration) {
                    result.accept(executable);
                }
            });
        });
    }

    private void dismissFileInspection() {
        inspectionGeneration++;
        PopupMenu menu = activeFileMenu;
        AlertDialog details = activeFileDetails;
        // Dialog dismiss callbacks may be posted: they must not invalidate a newer request.
        activeFileMenu = null;
        activeFileDetails = null;
        if (menu != null) menu.dismiss();
        if (details != null) details.dismiss();
    }

    private AlertDialog showBrowserDialog(AlertDialog dialog) {
        dialog.show();
        return dialog;
    }

    private void configureFileActions() {
        binding.localGameFileManagerToolbar.inflateMenu(R.menu.local_game_browser_actions);
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_new_file)
            .setOnMenuItemClickListener(item -> { showCreateDialog(false); return true; });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_new_folder)
            .setOnMenuItemClickListener(item -> { showCreateDialog(true); return true; });
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
                clipboardSources.clear(); blockedClipboard.clear(); updateFileActions(); return true;
            });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_select)
            .setOnMenuItemClickListener(item -> {
                if (!operating && !importing && localCurrent != null) {
                    selecting = true; updateSelectionAppearance();
                }
                return true;
            });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_select_all)
            .setOnMenuItemClickListener(item -> {
                if (operating || importing || !selecting) return true;
                List<File> eligible = selectableEntries();
                if (selectedPaths.size() == eligible.size()) selectedPaths.clear();
                else for (File file : eligible) selectedPaths.add(file.getAbsolutePath());
                updateSelectionAppearance(); return true;
            });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_cancel_selection)
            .setOnMenuItemClickListener(item -> { if (!operating) exitSelection(); return true; });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_copy_selection)
            .setOnMenuItemClickListener(item -> { setClipboard(selectedFiles(), false); return true; });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_cut_selection)
            .setOnMenuItemClickListener(item -> { setClipboard(selectedFiles(), true); return true; });
        binding.localGameFileManagerToolbar.getMenu().findItem(R.id.browser_delete_selection)
            .setOnMenuItemClickListener(item -> { confirmDelete(selectedFiles()); return true; });
        updateFileActions();
    }

    private void updateFileActions() {
        if (binding == null) return;
        if (localCurrent == null && !selecting) binding.localGameFileManagerToolbar.setNavigationIcon(null);
        else binding.localGameFileManagerToolbar.setNavigationIcon(R.drawable.local_games_ic_back);
        android.view.Menu menu = binding.localGameFileManagerToolbar.getMenu();
        if (menu.findItem(R.id.browser_paste) == null) return;
        boolean pending = !clipboardSources.isEmpty();
        boolean ready = !operating && !importing;
        menu.findItem(R.id.browser_new).setVisible(localCurrent != null && !selecting).setEnabled(ready);
        menu.findItem(R.id.browser_new_file).setEnabled(ready && localCurrent != null);
        menu.findItem(R.id.browser_new_folder).setEnabled(ready && localCurrent != null);
        menu.findItem(R.id.browser_paste).setVisible(pending && !selecting).setEnabled(ready && localCurrent != null)
            .setTitle(pending ? clipboardSources.size() == 1
                ? getString(R.string.browser_paste_named, clipboardSources.get(0).getName())
                : getString(R.string.browser_paste_count, clipboardSources.size()) : getString(R.string.browser_paste));
        menu.findItem(R.id.browser_cancel_clipboard).setVisible(pending).setEnabled(ready);
        menu.findItem(R.id.browser_show_hidden).setChecked(showHidden).setEnabled(ready);
        menu.findItem(R.id.browser_select).setVisible(localCurrent != null && !selecting).setEnabled(ready);
        menu.findItem(R.id.browser_select_all).setVisible(selecting).setEnabled(ready)
            .setTitle(!selectedPaths.isEmpty() && selectedPaths.size() == selectableEntries().size()
                ? R.string.browser_deselect_all : R.string.browser_select_all);
        menu.findItem(R.id.browser_cancel_selection).setVisible(selecting).setEnabled(ready);
        for (int id : new int[]{R.id.browser_copy_selection, R.id.browser_cut_selection, R.id.browser_delete_selection})
            menu.findItem(id).setVisible(selecting).setEnabled(ready && !selectedPaths.isEmpty());
        binding.localGameFileManagerToolbar.setTitle(selecting
            ? getString(R.string.browser_selected_count, selectedPaths.size()) : getString(R.string.local_game_file_manager_title));
    }

    private void showCreateDialog(boolean directory) {
        if (localCurrent == null || operating || importing || destroyed) return;
        File destination = localCurrent;
        FrostedDialogBuilder builder = new FrostedDialogBuilder(this);
        EditText nameInput = new EditText(builder.getContext());
        nameInput.setSingleLine(true);
        nameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        nameInput.setHint(R.string.browser_name);
        FrameLayout content = new FrameLayout(builder.getContext());
        content.setPadding(dp(24), dp(8), dp(24), 0);
        content.addView(nameInput, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog dialog = builder.setTitle(directory ? R.string.browser_new_folder : R.string.browser_new_file)
            .setView(content)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.browser_create, null)
            .create();
        showBrowserDialog(dialog);
        nameInput.requestFocus();
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (operating || importing || destroyed) return;
            String name = nameInput.getText().toString();
            if (!BrowserFileOperations.isValidName(name)) {
                nameInput.setError(getString(R.string.browser_invalid_name));
                return;
            }
            dialog.dismiss();
            runFileOperation(() -> fileOperations.create(destination, name, directory), () -> {});
        });
    }

    private boolean showFileMenu(View anchor, File target) {
        if (operating || importing || destroyed) return false;
        if (!fileOperations.canOperate(target)) return false;
        dismissFileInspection();
        PopupMenu menu = new PopupMenu(new ContextThemeWrapper(this,
            R.style.ThemeOverlay_TermuxLocalGames_FileBrowserPopup), anchor);
        menu.getMenu().add(0, OPEN, 1, R.string.browser_open);
        menu.getMenu().add(0, COPY, 2, R.string.browser_copy);
        menu.getMenu().add(0, CUT, 3, R.string.browser_cut);
        menu.getMenu().add(0, DELETE, 4, R.string.browser_delete);
        GameRuntimeBackendType backend = selectedImportBackend();
        menu.getMenu().add(0, SELECT, 6, R.string.browser_select);
        menu.setOnMenuItemClickListener(item -> {
            if (operating || importing) return true;
            switch (item.getItemId()) {
                case OPEN: openExternally(target); break;
                case COPY: case CUT:
                    setClipboard(java.util.Collections.singletonList(target), item.getItemId() == CUT);
                    break;
                case DELETE: confirmDelete(java.util.Collections.singletonList(target)); break;
                case IMPORT: importConfiguration(target, backend); break;
                case SELECT:
                    selecting = true; selectedPaths.add(target.getAbsolutePath()); updateSelectionAppearance(); break;
            }
            return true;
        });
        activeFileMenu = menu;
        menu.setOnDismissListener(dismissed -> {
            if (activeFileMenu == dismissed) {
                activeFileMenu = null;
                inspectionGeneration++;
            }
        });
        menu.show();
        if (!target.isDirectory()) inspectWindowsProgram(target, executable -> {
            if (executable && activeFileMenu == menu) {
                menu.getMenu().add(0, IMPORT, 5, importLabel(backend));
            }
        });
        return true;
    }

    private void openExternally(File target) {
        if (isWithin(sharedStorageRoot, target)) {
            ensureSharedStoragePermission(() -> openExternallyReady(target));
        } else openExternallyReady(target);
    }

    private void openExternallyReady(File target) {
        try {
            startActivity(Intent.createChooser(BrowserDocumentsProvider.openIntent(this, target),
                getString(R.string.browser_open_with)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.browser_no_open_app, Toast.LENGTH_LONG).show();
        } catch (IOException | SecurityException e) {
            showOperationFailure(e);
        }
    }

    private void confirmDelete(List<File> sources) {
        if (operating || importing || sources.isEmpty()) return;
        List<File> snapshot = new ArrayList<>(sources);
        StringBuilder names = new StringBuilder(getString(R.string.browser_delete_count_confirm, snapshot.size()));
        for (File file : snapshot) names.append('\n').append(file.getName());
        showBrowserDialog(new FrostedDialogBuilder(this).setTitle(R.string.browser_delete)
            .setView(batchMessageView(names.toString()))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.browser_delete, (dialog, which) ->
                runBatch(snapshot, null, BrowserBatchOperations.Action.DELETE)).create());
    }

    private void pasteIntoCurrent() {
        if (clipboardSources.isEmpty() || localCurrent == null || operating || importing) return;
        if (!blockedClipboard.isEmpty()) {
            showBatchMessage(getString(R.string.browser_batch_manual_check)); return;
        }
        runBatch(new ArrayList<>(clipboardSources), localCurrent,
            clipboardCut ? BrowserBatchOperations.Action.MOVE : BrowserBatchOperations.Action.COPY);
    }

    private void setClipboard(List<File> sources, boolean cut) {
        if (operating || importing || sources.isEmpty()) return;
        clipboardSources.clear(); clipboardSources.addAll(sources);
        blockedClipboard.clear(); clipboardCut = cut;
        exitSelection();
        Toast.makeText(this, getString(cut ? R.string.browser_cut_pending_count : R.string.browser_copy_pending_count,
            sources.size()), Toast.LENGTH_SHORT).show();
    }

    private void runBatch(List<File> sources, @Nullable File destination, BrowserBatchOperations.Action action) {
        if (operating || importing || destroyed || sources.isEmpty()) return;
        List<File> snapshot = new ArrayList<>(sources);
        Runnable submit = () -> {
            if (operating || importing || destroyed) return;
            try {
                selecting = false; selectedPaths.clear();
                batchModel.start(fileOperations, snapshot, destination, action);
            } catch (IOException | RuntimeException error) { showOperationFailure(error); }
        };
        boolean shared = destination != null && isWithin(sharedStorageRoot, destination);
        for (File source : snapshot) shared |= isWithin(sharedStorageRoot, source);
        if (shared) ensureSharedStoragePermission(submit); else submit.run();
    }

    private void renderBatchState(@Nullable BrowserBatchViewModel.Snapshot state) {
        if (state == null || destroyed || binding == null) return;
        operating = true;
        stopWatchingDirectory();
        binding.localGameFileManagerProgress.setVisibility(View.VISIBLE);
        updateSelectionAppearance();
        if (state.result == null) return;
        BrowserBatchOperations.Result result = state.result;
        if (state.action == BrowserBatchOperations.Action.MOVE) {
            clipboardSources.removeAll(result.completed);
            if (result.needsManualCheck() && result.failed != null)
                blockedClipboard.add(result.failed.getAbsolutePath());
        }
        if (state.action == BrowserBatchOperations.Action.DELETE) {
            clipboardSources.removeIf(source -> !fileOperations.canOperate(source));
        }
        blockedClipboard.removeIf(path -> clipboardSources.stream().noneMatch(source -> source.getAbsolutePath().equals(path)));
        operating = false;
        batchModel.consume(state);
        binding.localGameFileManagerProgress.setVisibility(View.GONE);
        File current = localCurrent;
        while (current != null && rootFor(current) != null && !current.isDirectory()) current = current.getParentFile();
        if (current != null && rootFor(current) != null) showLocal(current); else showHome();
        if (result.isSuccess()) {
            Toast.makeText(this, getString(R.string.browser_batch_complete, result.completed.size()), Toast.LENGTH_SHORT).show();
        } else {
            StringBuilder message = new StringBuilder(getString(R.string.browser_batch_result,
                result.completed.size(), result.failed == null ? 0 : 1, result.unattempted.size()));
            if (result.preflightFailure) message.append("\n\n").append(getString(R.string.browser_batch_preflight_failed));
            if (!result.completed.isEmpty()) {
                message.append("\n\n").append(getString(R.string.browser_batch_completed_items));
                for (File source : result.completed) message.append('\n').append(source.getPath());
            }
            if (result.failed != null) message.append("\n\n").append(getString(R.string.browser_batch_failed_item))
                .append('\n').append(result.failed.getPath());
            if (state.destination != null) message.append("\n→ ").append(state.destination.getPath());
            if (result.error != null) message.append('\n').append(result.error.getMessage());
            if (result.needsManualCheck()) message.append("\n\n").append(getString(R.string.browser_batch_manual_check));
            if (!result.unattempted.isEmpty()) {
                message.append("\n\n").append(getString(R.string.browser_batch_unattempted_items));
                for (File source : result.unattempted) message.append('\n').append(source.getPath());
            }
            showBatchMessage(message.toString());
        }
        updateFileActions();
    }

    private View batchMessageView(String message) {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        TextView text = new TextView(this);
        text.setText(message); text.setTextIsSelectable(true);
        text.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
        text.setPadding(dp(24), dp(12), dp(24), dp(12));
        scroll.addView(text, new android.widget.ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return scroll;
    }

    private void showBatchMessage(String message) {
        showBrowserDialog(new FrostedDialogBuilder(this).setTitle(R.string.local_game_file_manager_title)
            .setView(batchMessageView(message)).setPositiveButton(android.R.string.ok, null).create());
    }

    private interface FileOperation { File run() throws IOException; }

    private void runFileOperation(FileOperation operation, Runnable success) {
        if ((localCurrent != null && isWithin(sharedStorageRoot, localCurrent))
                || clipboardSources.stream().anyMatch(source -> isWithin(sharedStorageRoot, source))) {
            ensureSharedStoragePermission(() -> runFileOperationReady(operation, success));
        } else runFileOperationReady(operation, success);
    }

    private void runFileOperationReady(FileOperation operation, Runnable success) {
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
        showBrowserDialog(new FrostedDialogBuilder(this).setTitle(R.string.browser_operation_failed)
            .setMessage(getString(message) + "\n" + detail).setPositiveButton(android.R.string.ok, null).create());
    }

    private GameRuntimeBackendType selectedImportBackend() {
        return importBackend != null ? importBackend
            : CollectionToolbar.isProotSelected(this, "wine")
                ? GameRuntimeBackendType.ROOTFS_PROOT : GameRuntimeBackendType.GLIBC_TERMUX_BOX;
    }

    private String importLabel(GameRuntimeBackendType backend) {
        if (entryGameId != null) return getString(R.string.local_game_set_entry);
        return getString(backend == GameRuntimeBackendType.ROOTFS_PROOT
            ? R.string.local_game_import_proot_exe : R.string.local_game_import_native_exe);
    }

    private void importConfiguration(File file, GameRuntimeBackendType backend) {
        if (isWithin(sharedStorageRoot, file)) {
            ensureSharedStoragePermission(() -> importConfigurationReady(file, backend));
        } else importConfigurationReady(file, backend);
    }

    private void importConfigurationReady(File file, GameRuntimeBackendType backend) {
        if (destroyed || importing || operating) return;
        importing = true;
        updateFileActions();
        final String targetGameId = entryGameId;
        ioExecutor.execute(() -> {
            String gameId;
            String error = null;
            try {
                if (!isWindowsProgram(file)) throw new IOException(getString(R.string.browser_not_windows_program));
                if (targetGameId == null) {
                    gameId = QuickGameImport.importGame(getFilesDir(), file, backend);
                } else {
                    com.termux.ui.wine.runtime.RuntimeEditingStop.game(getFilesDir(),
                        LocalGames.requireHost(this), targetGameId);
                    synchronized (com.termux.ui.wine.runtime.RuntimeEditingStop.LOCK) {
                        QuickGameImport.setEntry(getFilesDir(), targetGameId, file);
                    }
                    gameId = targetGameId;
                }
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
                startActivity(targetGameId == null
                    ? LocalGames.createGameDetailIntent(this, importedId)
                    : LocalGames.createLibraryIntentShowingSetupConsole(this, null, true));
                finish();
            });
        });
    }

    private void clearRows() {
        visibleEntries.clear(); entryViews.clear(); entryChecks.clear();
        binding.localGameFileManagerItems.removeAllViews();
        binding.localGameFileManagerItems.setColumnCount(gridColumns());
    }

    private void addLocalEntry(File file, boolean directory) {
        String path = file.getAbsolutePath();
        addEntry(file.getName(), entrySubtitle(file), directory, view -> {
            if (operating || importing) return;
            if (selecting) toggleSelection(file);
            else if (directory) showLocal(file);
            else showFileDetails(file);
        }, view -> {
            if (selecting) toggleSelection(file); else showFileMenu(view, file);
        });
        View view = binding.localGameFileManagerItems.getChildAt(binding.localGameFileManagerItems.getChildCount() - 1);
        android.widget.CheckBox check = new androidx.appcompat.widget.AppCompatCheckBox(this);
        check.setClickable(false); check.setFocusable(false);
        check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        if (view instanceof FrameLayout) {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
            ((FrameLayout) view).addView(check, params);
        } else {
            ((LinearLayout) view).addView(check, 0, new LinearLayout.LayoutParams(dp(32), dp(32)));
        }
        visibleEntries.put(path, file); entryViews.put(path, view); entryChecks.put(path, check);
        androidx.core.view.ViewCompat.setAccessibilityDelegate(view, new androidx.core.view.AccessibilityDelegateCompat() {
            @Override public void onInitializeAccessibilityNodeInfo(View host,
                androidx.core.view.accessibility.AccessibilityNodeInfoCompat info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCheckable(selecting); info.setChecked(selectedPaths.contains(path));
            }
        });
    }

    private List<File> selectableEntries() {
        List<File> result = new ArrayList<>();
        for (File file : visibleEntries.values()) if (fileOperations.canOperate(file)) result.add(file);
        return result;
    }

    private List<File> selectedFiles() {
        List<File> result = new ArrayList<>();
        for (File file : selectableEntries()) if (selectedPaths.contains(file.getAbsolutePath())) result.add(file);
        return result;
    }

    private void toggleSelection(File file) {
        if (operating || importing || !fileOperations.canOperate(file)) return;
        String path = file.getAbsolutePath();
        if (!selectedPaths.remove(path)) selectedPaths.add(path);
        updateSelectionAppearance();
    }

    private void reconcileSelection() {
        selectedPaths.removeIf(path -> !visibleEntries.containsKey(path)
            || !fileOperations.canOperate(visibleEntries.get(path)));
        updateSelectionAppearance();
    }

    private void exitSelection() {
        selecting = false; selectedPaths.clear(); updateSelectionAppearance();
    }

    private void updateSelectionAppearance() {
        for (Map.Entry<String, View> item : entryViews.entrySet()) {
            boolean selected = selectedPaths.contains(item.getKey());
            item.getValue().setSelected(selected);
            android.widget.CheckBox check = entryChecks.get(item.getKey());
            check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            check.setChecked(selected);
        }
        updateFileActions();
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
