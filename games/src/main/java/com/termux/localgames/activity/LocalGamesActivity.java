package com.termux.localgames.activity;

import android.app.Dialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.localgames.R;
import com.termux.localgames.api.ComponentTasks;
import com.termux.localgames.api.RootfsBackupTasks;
import com.termux.localgames.api.RuntimeSetupTasks;
import com.termux.localgames.api.PrefixSetupTasks;
import com.termux.localgames.api.AppExperienceMode;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.artwork.GameArtworkLoader;
import com.termux.localgames.artwork.GameArtworkStore;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.components.catalog.ComponentCatalogItem;
import com.termux.localgames.components.catalog.ComponentCatalogRepository;
import com.termux.localgames.components.catalog.ComponentCatalogState;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentType;
import com.termux.localgames.components.install.ComponentInstallationReader;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FilePrefixSetupTaskRepository;
import com.termux.localgames.data.FileResetTaskRepository;
import com.termux.localgames.data.FileRootfsBackupTaskRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.FileRuntimeSetupTaskRepository;
import com.termux.localgames.data.GameAccessState;
import com.termux.localgames.data.GameLibraryItem;
import com.termux.localgames.data.GameLibraryRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.databinding.ActivityLocalGamesBinding;
import com.termux.localgames.databinding.DialogGameActionsBinding;
import com.termux.localgames.databinding.DialogGameLaunchBinding;
import com.termux.localgames.databinding.ItemLocalGamesComponentBinding;
import com.termux.localgames.databinding.ItemLocalGamesComponentSectionBinding;
import com.termux.localgames.databinding.ItemLocalGameBinding;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.PrefixSetupTask;
import com.termux.localgames.domain.ResetTarget;
import com.termux.localgames.domain.ResetTask;
import com.termux.localgames.domain.RootfsBackupTask;
import com.termux.localgames.domain.RootfsBackupTaskState;
import com.termux.localgames.domain.RuntimeReadinessState;
import com.termux.localgames.api.ResetTasks;
import com.termux.localgames.runtime.RuntimeEnvironmentStatus;
import com.termux.localgames.domain.RuntimeSetupTask;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.importer.SafGameAccessProbe;
import com.termux.localgames.recovery.GameUninstallPlan;
import com.termux.localgames.recovery.GameUninstaller;
import com.termux.shared.android.PermissionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root Activity owned by the standalone games feature module. */
public final class LocalGamesActivity extends AppCompatActivity {

    public static final String EXTRA_COMPONENT_GAME_ID =
        "com.termux.localgames.extra.COMPONENT_GAME_ID";
    private static final String INDEX_ASSET = "termux-box-packages/index-v1.json";
    private static final String TERMUX_GLIBC_RUNTIME_COMPONENT = "termux-glibc-runtime";
    private static final String STATE_SELECTED_TAB = "local_games.selected_tab";
    private static final int TAB_LIBRARY = 0;
    private static final int TAB_COMPONENTS = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int GAME_ACTION_ENGINE = 0;
    private static final int GAME_ACTION_EDIT = 1;
    private static final int GAME_ACTION_BACKUP = 2;
    private static final int GAME_ACTION_CONTROLS = 3;
    private static final int GAME_ACTION_REAUTHORIZE = 4;
    private static final int GAME_ACTION_REMOVE = 5;
    private static final long REFRESH_INTERVAL_MILLIS = 1000;
    // External storage (MANAGE_EXTERNAL_STORAGE on API 30+) is requested lazily, only the first
    // time the user actually touches Backup/Restore -- not at app startup -- since nothing else
    // on this screen needs it. The request can route through a system Settings screen (API 30+)
    // or a runtime permission dialog (below API 30), both of which return asynchronously via the
    // overrides below; pendingBackupStorageAction is what to resume once that result arrives.
    private static final int REQUEST_BACKUP_STORAGE_PERMISSION = 8401;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService catalogExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesComponentCatalog"));
    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesLibrary"));
    private final AtomicBoolean loadInFlight = new AtomicBoolean();
    private final AtomicBoolean libraryLoadInFlight = new AtomicBoolean();
    private final Runnable catalogRefresh = this::loadCatalogAsync;
    private final Map<String, ItemLocalGamesComponentBinding> componentRows =
        new LinkedHashMap<>();

    private ActivityLocalGamesBinding binding;
    private ComponentCatalogRepository catalogRepository;
    private FileRuntimeSetupTaskRepository runtimeSetupTaskRepository;
    private GameLibraryRepository libraryRepository;
    private GameArtworkLoader artworkLoader;
    private LocalGamesHost appHost;
    private String catalogInitializationError;
    private boolean started;
    private int selectedTab;
    private int libraryGeneration;
    private int libraryColumns = 2;
    private Runnable pendingBackupStorageAction;
    private Map<String, BuildingTask> buildingGameTasks = Collections.emptyMap();
    private final List<android.animation.ObjectAnimator> libraryCardBreathAnimators =
        new ArrayList<>();
    private RuntimeSetupTask latestRuntimeSetupTask;
    private boolean rootfsRuntimeInstalled;
    @Nullable private android.animation.ObjectAnimator glibcBladeBreathAnimator;
    @Nullable private android.animation.ObjectAnimator containerBladeBreathAnimator;
    @Nullable private String componentGameId;
    @Nullable private String componentContainerId;
    @Nullable private GameRuntimeBackendType componentBackend;

    /** A game whose runtime container is actively being built by a non-terminal setup task --
     *  see {@code loadLibraryAsync()}'s background pass and {@code renderGameRow()}. */
    private static final class BuildingTask {
        final String taskId;
        final boolean rootfs;
        BuildingTask(String taskId, boolean rootfs) {
            this.taskId = taskId;
            this.rootfs = rootfs;
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLocalGamesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        configureResponsiveLibrary();
        componentGameId = getIntent().getStringExtra(EXTRA_COMPONENT_GAME_ID);

        selectedTab = savedInstanceState == null
            ? (!TextUtils.isEmpty(componentGameId)
                ? TAB_COMPONENTS : TAB_LIBRARY)
            : savedInstanceState.getInt(STATE_SELECTED_TAB, TAB_LIBRARY);
        binding.localGamesToolbar.setNavigationOnClickListener(view -> {
            if (selectedTab == TAB_LIBRARY) finish();
            else showPage(TAB_LIBRARY);
        });
        binding.localGamesToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.local_games_action_help) return false;
            if (selectedTab == TAB_COMPONENTS) {
                GamesHelpDialog.show(this, R.string.local_games_components_title,
                    R.string.local_games_components_description);
            } else if (selectedTab == TAB_SETTINGS) {
                GamesHelpDialog.show(this, R.string.local_games_settings_title,
                    R.string.local_games_settings_help);
            } else {
                GamesHelpDialog.show(this, R.string.local_games_title,
                    R.string.local_games_import_hero_message);
            }
            return true;
        });
        binding.localGamesAddButton.setOnClickListener(view -> startImportFlow());
        binding.localGamesFileManagerButton.setOnClickListener(view -> startImportFlow());
        binding.localGamesComponentTaskConsole.setOnClickListener(view ->
            showActiveInstallationConsole());
        binding.localGamesSettingsButton.setOnClickListener(view -> showPage(TAB_SETTINGS));
        binding.localGamesBackupCreate.setOnClickListener(view -> confirmBackupCreate());
        configureOrientationButton();
        binding.localGamesNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.local_games_navigation_import) {
                startImportFlow();
                return false;
            }
            if (item.getItemId() == R.id.local_games_navigation_tools) {
                showTools();
                return false;
            }
            showPage(TAB_LIBRARY);
            return true;
        });
        initializeAppExperience();

        initializeCatalog();
        initializeLibrary();
        ComponentTasks.reconcile(this);
        RuntimeSetupTasks.reconcileAll(this);
        PrefixSetupTasks.reconcileAll(this);
        int initialPage = selectedTab;
        binding.localGamesNavigation.setSelectedItemId(R.id.local_games_navigation_library);
        showPage(initialPage);
        ResetTasks.reconcileAll(this);
        RootfsBackupTasks.reconcileAll(this);
        renderRuntimeStatus();
        mainHandler.post(this::consumePendingSetupConsoleIntent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mainHandler.post(this::consumePendingSetupConsoleIntent);
    }

    /** Surfaces the live console for a runtime warm-up task just enqueued by a runtime-options
     *  Save (see {@code GameRuntimeOptionsView.saveAndFinish()} and both of its hosts). Consumes
     *  the extras immediately so rotation or process recreation never re-shows it. */
    private void consumePendingSetupConsoleIntent() {
        Intent intent = getIntent();
        String taskId = intent.getStringExtra(LocalGames.EXTRA_PENDING_SETUP_TASK_ID);
        if (taskId == null) return;
        boolean rootfs = intent.getBooleanExtra(LocalGames.EXTRA_PENDING_SETUP_IS_ROOTFS, false);
        intent.removeExtra(LocalGames.EXTRA_PENDING_SETUP_TASK_ID);
        intent.removeExtra(LocalGames.EXTRA_PENDING_SETUP_IS_ROOTFS);
        Dialog dialog = rootfs ? RuntimeSetupConsoleDialog.show(this, taskId)
            : PrefixSetupConsoleDialog.show(this, taskId);
        dialog.setOnDismissListener(ignored -> loadLibraryAsync());
    }

    @Override
    protected void onStart() {
        super.onStart();
        started = true;
        if (selectedTab == TAB_COMPONENTS) scheduleCatalogRefresh(0);
        else if (selectedTab == TAB_LIBRARY) loadLibraryAsync();
        else renderAppExperience();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A reset runs behind RuntimeSetupConsoleDialog and control returns here once it
        // finishes -- re-check both environments so the emblem reflects the new state.
        renderRuntimeStatus();
    }

    @Override
    protected void onStop() {
        started = false;
        mainHandler.removeCallbacks(catalogRefresh);
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_BACKUP_STORAGE_PERMISSION) resumePendingBackupStorageAction();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_BACKUP_STORAGE_PERMISSION) resumePendingBackupStorageAction();
    }

    /** Runs {@code action} once external storage permission is confirmed granted -- either
     *  immediately (already granted) or after a round trip through the system permission/Settings
     *  UI (see onRequestPermissionsResult/onActivityResult above, which resume here). Backup and
     *  Restore are the only features on this screen that ever touch storage outside the app's own
     *  private files dir, so this is requested lazily here rather than at app startup. */
    private void ensureBackupStoragePermission(Runnable action) {
        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                this, -1, false)) {
            action.run();
            return;
        }
        pendingBackupStorageAction = action;
        PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
            this, REQUEST_BACKUP_STORAGE_PERMISSION, true);
    }

    private void resumePendingBackupStorageAction() {
        Runnable action = pendingBackupStorageAction;
        pendingBackupStorageAction = null;
        if (action == null) return;
        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                this, -1, true)) {
            action.run();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_SELECTED_TAB, selectedTab);
        super.onSaveInstanceState(outState);
    }

    private void initializeCatalog() {
        try (InputStream input = getAssets().open(INDEX_ASSET)) {
            ComponentIndex index = new ComponentIndexParser().parse(input);
            ComponentStoragePaths paths = new ComponentStoragePaths(getFilesDir());
            catalogRepository = new ComponentCatalogRepository(index,
                new FileComponentTaskRepository(paths.getTasksDirectory()),
                new ComponentInstallationReader(paths.getInstallDirectory()));
            GameStoragePaths gamePaths = new GameStoragePaths(getFilesDir());
            runtimeSetupTaskRepository = new FileRuntimeSetupTaskRepository(
                gamePaths.getRuntimeSetupTasksDirectory());
            if (!TextUtils.isEmpty(componentGameId)) {
                RuntimeProfile profile = new FileRuntimeProfileRepository(
                    gamePaths.getProfilesDirectory()).find(componentGameId).orElseThrow(() ->
                        new IOException("component_game_runtime_profile_missing"));
                componentBackend = profile.getRuntimeBackendType();
                componentContainerId = profile.getContainerId();
            }
        } catch (IOException error) {
            catalogInitializationError = safeMessage(error);
        }
    }

    private void configureResponsiveLibrary() {
        boolean landscape = getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        binding.localGamesPageTitle.setVisibility(landscape ? View.GONE : View.VISIBLE);
        binding.localGamesCount.setVisibility(landscape ? View.GONE : View.VISIBLE);
        ViewGroup parent = (ViewGroup) binding.localGamesShortcuts.getParent();
        if (parent != binding.localGamesLibraryContent) return;
        parent.removeView(binding.localGamesShortcuts);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(landscape ? 68 : 92));
        params.topMargin = dp(landscape ? 12 : 20);
        int emptyIndex = binding.localGamesLibraryContent.indexOfChild(
            binding.localGamesEmptyState);
        binding.localGamesLibraryContent.addView(binding.localGamesShortcuts,
            Math.max(0, emptyIndex), params);
    }

    private void configureOrientationButton() {
        boolean landscape = getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        binding.localGamesRuntimeEmblem.localGamesEmblemHubTap.setContentDescription(getString(
            landscape ? R.string.local_games_switch_portrait_description
                : R.string.local_games_switch_landscape_description));
        binding.localGamesRuntimeEmblem.localGamesEmblemHubTap.setOnClickListener(view ->
            setRequestedOrientation(landscape ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
    }

    private void initializeLibrary() {
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        libraryRepository = new GameLibraryRepository(
            new FileGameRepository(paths.getLibraryDirectory()),
            new SafGameAccessProbe(getContentResolver()));
        artworkLoader = new GameArtworkLoader(new GameArtworkStore(getFilesDir()));
    }

    private void showPage(int tab) {
        if (tab == TAB_COMPONENTS && TextUtils.isEmpty(componentGameId)) tab = TAB_LIBRARY;
        selectedTab = tab == TAB_COMPONENTS ? TAB_COMPONENTS :
            (tab == TAB_SETTINGS ? TAB_SETTINGS : TAB_LIBRARY);
        boolean showComponents = selectedTab == TAB_COMPONENTS;
        boolean showSettings = selectedTab == TAB_SETTINGS;
        binding.localGamesLibraryPage.setVisibility(
            !showComponents && !showSettings ? View.VISIBLE : View.GONE);
        binding.localGamesComponentsPage.setVisibility(showComponents ? View.VISIBLE : View.GONE);
        binding.localGamesSettingsPage.setVisibility(showSettings ? View.VISIBLE : View.GONE);
        updateChrome();
        if (showComponents && started) scheduleCatalogRefresh(0);
        else if (showSettings) renderAppExperience();
        else if (started) loadLibraryAsync();
    }

    private void updateChrome() {
        boolean landscape = getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        boolean gamesMode = appHost != null &&
            appHost.getAppExperienceMode() == AppExperienceMode.GAMES;
        binding.localGamesNavigation.setVisibility(
            selectedTab == TAB_LIBRARY && !landscape ? View.VISIBLE : View.GONE);
        binding.localGamesToolbar.setVisibility(
            selectedTab != TAB_LIBRARY || (!gamesMode && !landscape) ? View.VISIBLE : View.GONE);
        if (selectedTab == TAB_COMPONENTS) {
            binding.localGamesToolbar.setTitle(R.string.local_games_tab_components);
        } else if (selectedTab == TAB_SETTINGS) {
            binding.localGamesToolbar.setTitle(R.string.local_games_settings_title);
        } else {
            binding.localGamesToolbar.setTitle(R.string.local_games_title);
        }
    }

    private void showTools() {
        CharSequence[] actions = {
            getString(R.string.local_games_shortcut_settings),
            getString(R.string.local_games_open_terminal)
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_games_tools_title)
            .setItems(actions, (dialog, which) -> {
                if (which == 0) showPage(TAB_SETTINGS);
                else if (appHost != null) appHost.openTerminal();
            })
            .show();
    }

    private void initializeAppExperience() {
        try {
            appHost = LocalGames.requireHost(this);
            binding.localGamesAppModeTerminal.setOnClickListener(view ->
                confirmAppExperienceSwitch(AppExperienceMode.TERMINAL));
            binding.localGamesAppModeGames.setOnClickListener(view ->
                confirmAppExperienceSwitch(AppExperienceMode.GAMES));
            binding.localGamesOpenTerminal.setOnClickListener(view -> appHost.openTerminal());
            renderAppExperience();
            updateChrome();
        } catch (RuntimeException error) {
            appHost = null;
            binding.localGamesAppModeGroup.setEnabled(false);
            binding.localGamesOpenTerminal.setEnabled(false);
        }
    }

    private void renderAppExperience() {
        if (binding == null || appHost == null) return;
        AppExperienceMode mode = appHost.getAppExperienceMode();
        binding.localGamesAppModeGroup.check(mode == AppExperienceMode.GAMES
            ? R.id.local_games_app_mode_games : R.id.local_games_app_mode_terminal);
    }

    private void confirmAppExperienceSwitch(AppExperienceMode target) {
        if (appHost == null || target == appHost.getAppExperienceMode()) {
            renderAppExperience();
            return;
        }
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_games_app_mode_restart_title)
            .setMessage(R.string.local_games_app_mode_restart_message)
            .setNegativeButton(android.R.string.cancel, (dialog, which) -> renderAppExperience())
            .setPositiveButton(R.string.local_games_app_mode_restart_confirm, (dialog, which) -> {
                try {
                    appHost.switchAppExperienceMode(target);
                } catch (RuntimeException error) {
                    Toast.makeText(this, R.string.local_games_app_mode_restart_failed,
                        Toast.LENGTH_LONG).show();
                    renderAppExperience();
                }
            })
            .setOnCancelListener(dialog -> renderAppExperience())
            .show();
    }

    private void loadLibraryAsync() {
        if (!started || selectedTab != TAB_LIBRARY ||
            !libraryLoadInFlight.compareAndSet(false, true)) return;
        int generation = ++libraryGeneration;
        binding.localGamesLibraryLoading.show();
        binding.localGamesLibraryError.setVisibility(View.GONE);
        libraryExecutor.execute(() -> {
            List<GameLibraryItem> items = null;
            Map<String, BuildingTask> building = new HashMap<>();
            String error = null;
            try {
                items = libraryRepository.load();
                GameStoragePaths gamePaths = new GameStoragePaths(getFilesDir());
                FileRuntimeProfileRepository profiles =
                    new FileRuntimeProfileRepository(gamePaths.getProfilesDirectory());
                Map<String, String> activeRootfsSetupByContainer = new HashMap<>();
                for (RuntimeSetupTask task : new FileRuntimeSetupTaskRepository(
                        gamePaths.getRuntimeSetupTasksDirectory()).list()) {
                    if (!task.getState().isTerminal()) {
                        activeRootfsSetupByContainer.put(task.getContainerId(), task.getTaskId());
                    }
                }
                Map<String, String> activePrefixSetupByGame = new HashMap<>();
                for (PrefixSetupTask task : new FilePrefixSetupTaskRepository(
                        gamePaths.getPrefixSetupTasksDirectory()).list()) {
                    if (!task.getState().isTerminal()) {
                        activePrefixSetupByGame.put(task.getGameId(), task.getTaskId());
                    }
                }
                for (GameLibraryItem item : items) {
                    profiles.find(item.getGame().getId()).ifPresent(profile -> {
                        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
                            String taskId = activeRootfsSetupByContainer.get(
                                profile.getContainerId());
                            if (taskId != null) {
                                building.put(item.getGame().getId(), new BuildingTask(taskId, true));
                            }
                        } else if (profile.getRuntimeBackendType() ==
                                GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
                            String taskId = activePrefixSetupByGame.get(item.getGame().getId());
                            if (taskId != null) {
                                building.put(item.getGame().getId(), new BuildingTask(taskId, false));
                            }
                        }
                    });
                }
            } catch (IOException loadError) {
                error = safeMessage(loadError);
            }
            List<GameLibraryItem> loaded = items;
            Map<String, BuildingTask> loadedBuilding = building;
            String failure = error;
            mainHandler.post(() -> {
                libraryLoadInFlight.set(false);
                if (!started || binding == null || selectedTab != TAB_LIBRARY ||
                    generation != libraryGeneration) return;
                if (failure == null) {
                    buildingGameTasks = loadedBuilding;
                    renderLibrary(loaded, generation);
                } else renderLibraryError(failure);
            });
        });
    }

    private void renderLibrary(List<GameLibraryItem> items, int generation) {
        binding.localGamesLibraryLoading.hide();
        binding.localGamesLibraryError.setVisibility(View.GONE);
        binding.localGamesLibraryItems.removeAllViews();
        // Rows are fully reinflated on every pass -- cancel animators from the previous pass so
        // they do not keep running (and ticking the UI thread) against now-detached views.
        for (android.animation.ObjectAnimator animator : libraryCardBreathAnimators) {
            animator.cancel();
        }
        libraryCardBreathAnimators.clear();
        binding.localGamesCount.setText(getString(R.string.local_games_my_games_count,
            items.size()));
        binding.localGamesEmptyState.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
        int gridWidth = binding.localGamesLibraryItems.getWidth();
        if (gridWidth <= 0) gridWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
        boolean landscape = getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        libraryColumns = landscape ? Math.max(1, Math.min(4, gridWidth / dp(236))) : 2;
        binding.localGamesLibraryItems.setColumnCount(libraryColumns);
        for (int index = 0; index < items.size(); index++) {
            GameLibraryItem item = items.get(index);
            ItemLocalGameBinding row = ItemLocalGameBinding.inflate(
                getLayoutInflater(), binding.localGamesLibraryItems, false);
            applyGameCardLayout(row.getRoot(), index);
            renderGameRow(row, item, generation);
            binding.localGamesLibraryItems.addView(row.getRoot());
        }
    }

    private void applyGameCardLayout(View card, int index) {
        int gridWidth = binding.localGamesLibraryItems.getWidth();
        if (gridWidth <= 0) gridWidth = getResources().getDisplayMetrics().widthPixels - dp(32);
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = Math.max(dp(140), gridWidth / libraryColumns - dp(8));
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(index % libraryColumns);
        params.rowSpec = GridLayout.spec(index / libraryColumns);
        params.setMargins(dp(4), dp(4), dp(4), dp(12));
        card.setLayoutParams(params);
    }

    private void renderGameRow(ItemLocalGameBinding row, GameLibraryItem item, int generation) {
        Game game = item.getGame();
        row.localGameName.setText(game.getName());
        row.localGameExecutable.setText(game.getExecutable());
        row.localGameAccessStatus.setText(accessLabel(item.getAccessState()));
        row.localGameAccessStatus.setTextColor(ContextCompat.getColor(this,
            item.getAccessState() == GameAccessState.ACCESSIBLE
                ? R.color.local_games_success : R.color.local_games_error));
        row.localGameLastPlayed.setText(game.getLastPlayedAt() == 0
            ? getString(R.string.local_game_never_played)
            : getString(R.string.local_game_last_played, DateUtils.formatDateTime(this,
                game.getLastPlayedAt(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME)));
        BuildingTask building = buildingGameTasks.get(game.getId());
        applyCardBuildingState(row.getRoot(), building != null);
        row.getRoot().setOnClickListener(view -> {
            if (building != null) {
                Dialog dialog = building.rootfs
                    ? RuntimeSetupConsoleDialog.show(this, building.taskId)
                    : PrefixSetupConsoleDialog.show(this, building.taskId);
                dialog.setOnDismissListener(ignored -> loadLibraryAsync());
                return;
            }
            showLaunchPresentation(item);
        });
        row.getRoot().setFocusable(true);
        row.localGameDetails.setOnClickListener(view -> startActivity(
            LocalGames.createRuntimeOptionsIntent(this, game.getId())));
        row.localGameMore.setOnClickListener(view -> showActionsTextMenu(item, view));
        if (!game.getArtworkUri().isEmpty()) {
            libraryExecutor.execute(() -> {
                Bitmap cover = artworkLoader.load(game.getArtworkUri(), 256);
                mainHandler.post(() -> {
                    if (!started || binding == null || selectedTab != TAB_LIBRARY ||
                        generation != libraryGeneration || cover == null) return;
                    row.localGameCover.setPadding(0, 0, 0, 0);
                    row.localGameCover.setImageBitmap(cover);
                });
            });
        }
    }

    private void showLaunchPresentation(GameLibraryItem item) {
        Game game = item.getGame();
        DialogGameLaunchBinding content = DialogGameLaunchBinding.inflate(getLayoutInflater());
        content.gameLaunchSheetName.setText(game.getName());
        content.gameLaunchSheetStatus.setText(accessLabel(item.getAccessState()));
        content.gameLaunchSheetStatus.setTextColor(ContextCompat.getColor(this,
            item.getAccessState() == GameAccessState.ACCESSIBLE
                ? R.color.local_games_success : R.color.local_games_error));
        content.gameLaunchSheetStart.setEnabled(
            item.getAccessState() == GameAccessState.ACCESSIBLE);

        Dialog dialog = createPresentationDialog(content.getRoot());
        content.gameLaunchSheetClose.setOnClickListener(view -> dialog.dismiss());
        content.gameLaunchSheetStart.setOnClickListener(view -> {
            dialog.dismiss();
            startActivity(LocalGames.createGameLaunchIntent(this, game.getId()));
        });
        content.gameLaunchSheetLayout.setOnClickListener(view -> {
            dialog.dismiss();
            showActionsPresentation(item);
        });
        dialog.setOnShowListener(ignored -> configureLaunchPresentationWindow(dialog));
        dialog.show();

        if (!game.getArtworkUri().isEmpty()) {
            libraryExecutor.execute(() -> {
                Bitmap cover = artworkLoader.load(game.getArtworkUri(), 512);
                mainHandler.post(() -> {
                    if (binding == null || !dialog.isShowing() || cover == null) return;
                    content.gameLaunchSheetCover.setPadding(0, 0, 0, 0);
                    content.gameLaunchSheetCover.setImageBitmap(cover);
                });
            });
        }
    }

    private void showActionsPresentation(GameLibraryItem item) {
        Game game = item.getGame();
        DialogGameActionsBinding content = DialogGameActionsBinding.inflate(getLayoutInflater());
        Dialog dialog = createPresentationDialog(content.getRoot());
        content.gameActionsClose.setOnClickListener(view -> dialog.dismiss());
        content.gameActionsEngine.setOnClickListener(view -> {
            dialog.dismiss();
            performGameAction(item, GAME_ACTION_ENGINE);
        });
        content.gameActionsEdit.setOnClickListener(view -> {
            dialog.dismiss();
            performGameAction(item, GAME_ACTION_EDIT);
        });
        content.gameActionsAssets.setOnClickListener(view -> {
            dialog.dismiss();
            performGameAction(item, GAME_ACTION_BACKUP);
        });
        content.gameActionsInput.setOnClickListener(view -> {
            dialog.dismiss();
            performGameAction(item, GAME_ACTION_CONTROLS);
        });
        boolean reauthorize = item.getAccessState() != GameAccessState.ACCESSIBLE;
        content.gameActionsReauthorize.setVisibility(reauthorize ? View.VISIBLE : View.GONE);
        content.gameActionsReauthorize.setOnClickListener(view -> {
            dialog.dismiss();
            performGameAction(item, GAME_ACTION_REAUTHORIZE);
        });
        content.gameActionsRemove.setOnClickListener(view -> {
            dialog.dismiss();
            performGameAction(item, GAME_ACTION_REMOVE);
        });
        dialog.setOnShowListener(ignored -> configureLaunchPresentationWindow(dialog));
        dialog.show();
    }

    /** Card overflow is intentionally text-only; the launch-sheet shortcut uses the icon grid. */
    private void showActionsTextMenu(GameLibraryItem item, View anchor) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.setBackgroundResource(R.drawable.local_games_bg_panel);
        int width = textPopupWidth(item);
        PopupWindow popup = new PopupWindow(content, width,
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(10));
        addTextPopupAction(content, popup, item, R.string.local_game_action_engine,
            GAME_ACTION_ENGINE);
        addTextPopupAction(content, popup, item, R.string.local_game_action_edit,
            GAME_ACTION_EDIT);
        addTextPopupAction(content, popup, item, R.string.local_game_action_assets,
            GAME_ACTION_BACKUP);
        addTextPopupAction(content, popup, item, R.string.local_game_action_input,
            GAME_ACTION_CONTROLS);
        if (item.getAccessState() != GameAccessState.ACCESSIBLE) {
            addTextPopupAction(content, popup, item, R.string.local_game_action_reauthorize,
                GAME_ACTION_REAUTHORIZE);
        }
        addTextPopupAction(content, popup, item, R.string.local_game_action_remove,
            GAME_ACTION_REMOVE);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        popup.showAsDropDown(anchor, anchor.getWidth(),
            -anchor.getHeight() - content.getMeasuredHeight());
    }

    private void addTextPopupAction(LinearLayout parent, PopupWindow popup,
                                    GameLibraryItem item, int label, int action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(ContextCompat.getColor(this, action == GAME_ACTION_REMOVE
            ? R.color.local_games_error : R.color.local_games_on_surface));
        row.setTextSize(16);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setOnClickListener(view -> {
            popup.dismiss();
            performGameAction(item, action);
        });
        parent.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)));
    }

    private int textPopupWidth(GameLibraryItem item) {
        int[] labels = item.getAccessState() == GameAccessState.ACCESSIBLE
            ? new int[] { R.string.local_game_action_engine, R.string.local_game_action_edit,
                R.string.local_game_action_assets, R.string.local_game_action_input,
                R.string.local_game_action_remove }
            : new int[] { R.string.local_game_action_engine, R.string.local_game_action_edit,
                R.string.local_game_action_assets, R.string.local_game_action_input,
                R.string.local_game_action_reauthorize, R.string.local_game_action_remove };
        android.text.TextPaint paint = new android.text.TextPaint();
        paint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 16f);
        float widest = 0f;
        for (int label : labels) widest = Math.max(widest, paint.measureText(getString(label)));
        return Math.round(widest) + dp(48);
    }

    private void performGameAction(GameLibraryItem item, int action) {
        Game game = item.getGame();
        switch (action) {
            case GAME_ACTION_ENGINE:
            case GAME_ACTION_CONTROLS:
                startActivity(LocalGames.createRuntimeOptionsIntent(this, game.getId()));
                return;
            case GAME_ACTION_EDIT:
                startActivity(LocalGames.createGameDetailIntent(this, game.getId()));
                return;
            case GAME_ACTION_BACKUP:
                startActivity(LocalGames.createGameAssetsIntent(this, game.getId()));
                return;
            case GAME_ACTION_REAUTHORIZE:
                startActivity(LocalGames.createReauthorizeIntent(this, game.getRootUri()));
                return;
            case GAME_ACTION_REMOVE:
                confirmRemove(game);
                return;
            default:
                throw new IllegalArgumentException("unknown_game_action");
        }
    }

    private void configurePresentationWindow(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.dimAmount = 0.78f;
        boolean landscape = getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        attributes.gravity = landscape ? Gravity.CENTER : Gravity.BOTTOM;
        window.setAttributes(attributes);
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = landscape ? Math.min(screenWidth - dp(96), dp(680)) : screenWidth - dp(16);
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void configureLaunchPresentationWindow(Dialog dialog) {
        configurePresentationWindow(dialog);
        Window window = dialog.getWindow();
        if (window == null) return;
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getResources().getDisplayMetrics().heightPixels;
        int maxWidth = Math.round(screenWidth * 0.82f);
        int maxHeight = Math.round(screenHeight * 0.76f);
        int height = Math.min(maxHeight, Math.round(maxWidth * 3f / 4f));
        int width = Math.round(height * 4f / 3f);
        window.setLayout(width, height);
    }

    private Dialog createPresentationDialog(View content) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(content);
        return dialog;
    }

    private void confirmRemove(Game game) {
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.local_game_detail_delete_title, game.getName()))
            .setMessage(R.string.local_game_detail_delete_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_game_detail_delete_confirm,
                (dialog, which) -> removeGame(game))
            .show();
    }

    private void removeGame(Game game) {
        int generation = ++libraryGeneration;
        binding.localGamesLibraryLoading.show();
        libraryExecutor.execute(() -> {
            String error = null;
            try {
                new GameUninstaller(getFilesDir()).execute(
                    GameUninstallPlan.keepPrivateAssets(game.getId()));
            } catch (IOException | RuntimeException removeError) {
                error = safeMessage(removeError);
            }
            String failure = error;
            mainHandler.post(() -> {
                if (binding == null || generation != libraryGeneration) return;
                binding.localGamesLibraryLoading.hide();
                if (failure == null) {
                    Toast.makeText(this, R.string.local_game_remove_complete,
                        Toast.LENGTH_SHORT).show();
                    loadLibraryAsync();
                } else {
                    Toast.makeText(this,
                        getString(R.string.local_game_detail_delete_failed, failure),
                        Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private int accessLabel(GameAccessState state) {
        switch (state) {
            case ACCESSIBLE: return R.string.local_game_accessible;
            case PERMISSION_LOST: return R.string.local_game_permission_lost;
            default: return R.string.local_game_provider_unavailable;
        }
    }

    private void renderLibraryError(String message) {
        binding.localGamesLibraryLoading.hide();
        binding.localGamesLibraryItems.removeAllViews();
        binding.localGamesEmptyState.setVisibility(View.GONE);
        binding.localGamesLibraryError.setText(getString(
            R.string.local_games_library_load_failed, message));
        binding.localGamesLibraryError.setVisibility(View.VISIBLE);
    }

    private void scheduleCatalogRefresh(long delayMillis) {
        mainHandler.removeCallbacks(catalogRefresh);
        mainHandler.postDelayed(catalogRefresh, delayMillis);
    }

    private void loadCatalogAsync() {
        if (!started || selectedTab != TAB_COMPONENTS || !loadInFlight.compareAndSet(false, true)) {
            return;
        }
        if (catalogRepository == null) {
            loadInFlight.set(false);
            renderCatalogError(catalogInitializationError == null
                ? getString(R.string.local_games_component_state_failed)
                : catalogInitializationError);
            return;
        }
        catalogExecutor.execute(() -> {
            List<ComponentCatalogItem> items = null;
            String error = null;
            RuntimeSetupTask setupTask = null;
            boolean runtimeInstalled = false;
            try {
                items = catalogRepository.load();
                items = filterScopedComponents(items);
            } catch (IOException loadError) {
                error = safeMessage(loadError);
            }
            if (componentBackend == GameRuntimeBackendType.ROOTFS_PROOT) {
                try {
                    setupTask = latestRuntimeSetupTask();
                } catch (IOException ignored) {
                    // A damaged task record must not hide the component catalog or reinstall action.
                }
                if (componentContainerId != null) {
                    GameStoragePaths gamePaths = new GameStoragePaths(getFilesDir());
                    runtimeInstalled = gamePaths.getSharedRootfsDirectory().isDirectory() &&
                        gamePaths.getContainerPrefixDirectory(componentContainerId,
                            GameRuntimeBackendType.ROOTFS_PROOT).isDirectory() &&
                        gamePaths.getContainerHomeDirectory(componentContainerId,
                            GameRuntimeBackendType.ROOTFS_PROOT).isDirectory();
                }
            }
            List<ComponentCatalogItem> loadedItems = items;
            String loadedError = error;
            RuntimeSetupTask loadedSetupTask = setupTask;
            boolean loadedRuntimeInstalled = runtimeInstalled;
            mainHandler.post(() -> {
                loadInFlight.set(false);
                if (!started || binding == null || selectedTab != TAB_COMPONENTS) return;
                if (loadedError == null) {
                    latestRuntimeSetupTask = loadedSetupTask;
                    rootfsRuntimeInstalled = loadedRuntimeInstalled;
                    renderCatalog(loadedItems);
                }
                else renderCatalogError(loadedError);
                scheduleCatalogRefresh(REFRESH_INTERVAL_MILLIS);
            });
        });
    }

    @Nullable
    private RuntimeSetupTask latestRuntimeSetupTask() throws IOException {
        if (runtimeSetupTaskRepository == null) return null;
        RuntimeSetupTask latest = null;
        for (RuntimeSetupTask task : runtimeSetupTaskRepository.list()) {
            if (!"debian-13-games-rootfs".equals(task.getPackageName())) continue;
            if (!task.getContainerId().equals(componentContainerId)) continue;
            if (latest == null || task.getCreatedAt() > latest.getCreatedAt()) latest = task;
        }
        return latest;
    }

    private List<ComponentCatalogItem> filterScopedComponents(List<ComponentCatalogItem> items)
        throws IOException {
        if (componentBackend == null) throw new IOException("component_runtime_scope_missing");
        List<ComponentCatalogItem> result = new java.util.ArrayList<>();
        for (ComponentCatalogItem item : items) {
            if (item.getDescriptor().supportsBackend(componentBackend)) result.add(item);
        }
        return result;
    }

    private void renderCatalog(List<ComponentCatalogItem> items) {
        binding.localGamesComponentsLoading.setVisibility(View.GONE);
        binding.localGamesComponentsError.setVisibility(View.GONE);
        renderActiveInstallationConsoleAction();
        ensureComponentRows(items);
        for (ComponentCatalogItem item : items) {
            renderComponent(componentRows.get(item.getDescriptor().getId()), item);
        }
    }

    private void ensureComponentRows(List<ComponentCatalogItem> items) {
        if (componentRows.size() == items.size()) return;
        componentRows.clear();
        binding.localGamesComponentSections.removeAllViews();
        for (ComponentType type : ComponentType.values()) {
            List<ComponentCatalogItem> sectionItems = new java.util.ArrayList<>();
            for (ComponentCatalogItem item : items) {
                if (item.getDescriptor().getType() == type) sectionItems.add(item);
            }
            sectionItems.sort((left, right) -> ComponentIndex.compareForPresentation(
                left.getDescriptor(), right.getDescriptor()));
            ItemLocalGamesComponentSectionBinding section = null;
            for (ComponentCatalogItem item : sectionItems) {
                if (section == null) {
                    section = ItemLocalGamesComponentSectionBinding.inflate(
                        getLayoutInflater(), binding.localGamesComponentSections, false);
                    configureComponentSection(section, type);
                    binding.localGamesComponentSections.addView(section.getRoot());
                }
                ItemLocalGamesComponentBinding row = ItemLocalGamesComponentBinding.inflate(
                    getLayoutInflater(), section.localGamesComponentSectionItems, false);
                section.localGamesComponentSectionItems.addView(row.getRoot());
                componentRows.put(item.getDescriptor().getId(), row);
            }
        }
    }

    private void configureComponentSection(ItemLocalGamesComponentSectionBinding section,
                                           ComponentType type) {
        setComponentSectionExpanded(section, type, false);
        section.localGamesComponentSectionTitle.setOnClickListener(view ->
            setComponentSectionExpanded(section, type,
                section.localGamesComponentSectionItems.getVisibility() != View.VISIBLE));
    }

    private void setComponentSectionExpanded(ItemLocalGamesComponentSectionBinding section,
                                             ComponentType type, boolean expanded) {
        int label = componentTypeLabel(type);
        section.localGamesComponentSectionTitle.setText(getString(expanded
            ? R.string.local_games_component_section_expanded
            : R.string.local_games_component_section_collapsed, getString(label)));
        section.localGamesComponentSectionTitle.setContentDescription(getString(expanded
            ? R.string.local_games_component_section_collapse
            : R.string.local_games_component_section_expand, getString(label)));
        section.localGamesComponentSectionItems.setVisibility(expanded ? View.VISIBLE : View.GONE);
    }

    private void renderComponent(ItemLocalGamesComponentBinding row,
                                 ComponentCatalogItem item) {
        ComponentDescriptor descriptor = item.getDescriptor();
        row.localGamesComponentName.setText(descriptor.getDisplayName());
        row.localGamesComponentMetadata.setText(descriptor.getVersionName());
        row.localGamesComponentHelp.setOnClickListener(view -> GamesHelpDialog.show(this,
            descriptor.getDisplayName(), componentSummary(descriptor)));
        boolean activationRequired = requiresRuntimeActivation(item);
        boolean rootfsSource = isRootfsSource(item);
        RuntimeSetupTask setupTask = rootfsSource ? latestRuntimeSetupTask : null;
        ComponentTask task = item.getTask().orElse(null);
        configurePrimaryAction(row, item, task, activationRequired, setupTask);
    }

    private static boolean isRootfsSource(ComponentCatalogItem item) {
        return "hangover-11.9-debian13-source".equals(item.getDescriptor().getId());
    }

    private int componentTypeLabel(ComponentType type) {
        switch (type) {
            case IMAGE_FS: return R.string.local_games_components_image_fs;
            case CONTAINER: return R.string.local_games_components_container;
            case GPU_DRIVER: return R.string.local_games_components_gpu;
            case DX_WRAPPER: return R.string.local_games_components_dx;
            case TRANSLATOR: return R.string.local_games_components_translator;
            case GENERAL_COMPONENT: return R.string.local_games_components_general;
            default: return R.string.local_games_components_support;
        }
    }

    private String componentBadges(ComponentDescriptor descriptor) {
        StringBuilder result = new StringBuilder();
        if (descriptor.isRecommended()) {
            result.append(getString(R.string.local_games_component_badge_recommended));
        }
        if (descriptor.isBase()) {
            if (result.length() > 0) {
                result.append(getString(R.string.local_games_component_badge_separator));
            }
            result.append(getString(R.string.local_games_component_badge_base));
        }
        return result.toString();
    }

    private String componentSummary(ComponentDescriptor descriptor) {
        switch (descriptor.getId()) {
            case "hangover-11.9-debian13-source":
                return getString(R.string.local_games_component_summary_imagefs);
            case "turnip":
                return getString(R.string.local_games_component_summary_turnip);
            case "virgl-mesa":
                return getString(R.string.local_games_component_summary_virgl);
            case "dxvk":
                return getString(R.string.local_games_component_summary_dxvk);
            case "wined3d":
                return getString(R.string.local_games_component_summary_wined3d);
            case "box64-binaries":
                return getString(R.string.local_games_component_summary_box64);
            case "prefix-apps":
                return getString(R.string.local_games_component_summary_prefix_apps);
            case "libudev":
                return getString(R.string.local_games_component_summary_libudev);
            case "en-ru-locale":
                return getString(R.string.local_games_component_summary_locale);
            case "termux-glibc-runtime":
                return getString(R.string.local_games_component_summary_glibc);
            case "scripts":
                return getString(R.string.local_games_component_summary_scripts);
            default:
                return descriptor.getType() == ComponentType.CONTAINER
                    ? getString(R.string.local_games_component_summary_wine)
                    : descriptor.getSummary();
        }
    }

    private void configurePrimaryAction(ItemLocalGamesComponentBinding row,
                                        ComponentCatalogItem item, ComponentTask task,
                                        boolean activationRequired,
                                        @Nullable RuntimeSetupTask setupTask) {
        int title;
        View.OnClickListener action;
        if (task != null && task.getState().shouldRecoverAutomatically()) {
            row.localGamesComponentPrimaryAction.setText(
                R.string.local_games_component_view_process);
            row.localGamesComponentPrimaryAction.setOnClickListener(view ->
                showComponentInstallationConsole(task));
            row.localGamesComponentPrimaryAction.setEnabled(true);
            row.localGamesComponentPrimaryAction.setVisibility(View.VISIBLE);
            return;
        }
        if (activationRequired) {
            row.localGamesComponentPrimaryAction.setText(
                R.string.local_games_component_activate);
            row.localGamesComponentPrimaryAction.setOnClickListener(view -> {
                disableActions(row);
                ComponentTasks.activate(this, item.getDescriptor().getId());
                scheduleCatalogRefresh(250);
            });
            row.localGamesComponentPrimaryAction.setEnabled(true);
            row.localGamesComponentPrimaryAction.setVisibility(View.VISIBLE);
            return;
        }
        if (item.getState() == ComponentCatalogState.INSTALLED &&
            isRootfsSource(item)) {
            if (setupTask != null && !setupTask.getState().isTerminal()) {
                row.localGamesComponentPrimaryAction.setText(
                    R.string.local_games_runtime_setup_view_log_action);
                row.localGamesComponentPrimaryAction.setOnClickListener(view ->
                    RuntimeSetupConsoleDialog.show(this, setupTask.getTaskId()));
                row.localGamesComponentPrimaryAction.setEnabled(true);
                row.localGamesComponentPrimaryAction.setVisibility(View.VISIBLE);
                return;
            }
            if (rootfsRuntimeInstalled) {
                row.localGamesComponentPrimaryAction.setText(
                    R.string.local_games_runtime_setup_reinstall_action);
                row.localGamesComponentPrimaryAction.setOnClickListener(view -> {
                    if (showActiveInstallationConsole()) return;
                    disableActions(row);
                    String taskId = RuntimeSetupTasks.enqueue(this,
                        "debian-13-games-rootfs", componentContainerId);
                    RuntimeSetupConsoleDialog.show(this, taskId);
                    scheduleCatalogRefresh(150);
                });
                row.localGamesComponentPrimaryAction.setEnabled(true);
                row.localGamesComponentPrimaryAction.setVisibility(View.VISIBLE);
                return;
            }
            row.localGamesComponentPrimaryAction.setText(
                R.string.local_games_runtime_setup_action);
            row.localGamesComponentPrimaryAction.setOnClickListener(view -> {
                if (showActiveInstallationConsole()) return;
                disableActions(row);
                String taskId = RuntimeSetupTasks.enqueue(this,
                    "debian-13-games-rootfs", componentContainerId);
                RuntimeSetupConsoleDialog.show(this, taskId);
                Toast.makeText(this, getString(R.string.local_games_runtime_setup_started,
                    taskId), Toast.LENGTH_SHORT).show();
                scheduleCatalogRefresh(150);
            });
            row.localGamesComponentPrimaryAction.setEnabled(true);
            row.localGamesComponentPrimaryAction.setVisibility(View.VISIBLE);
            return;
        }
        switch (item.getState()) {
            case NOT_INSTALLED:
            case UPDATE_AVAILABLE:
                title = isTermuxGlibcRuntime(item.getDescriptor()) &&
                    isRuntimeComponentAvailable(item.getDescriptor())
                    ? R.string.local_games_component_reinstall
                    : isTermuxGlibcRuntime(item.getDescriptor())
                        ? R.string.local_games_component_install
                        : R.string.local_games_component_download;
                action = view -> {
                    if (showActiveInstallationConsole()) return;
                    disableActions(row);
                    String taskId = ComponentTasks.enqueue(this, item.getDescriptor().getId());
                    showComponentInstallationConsole(item.getDescriptor().getId(), taskId);
                    scheduleCatalogRefresh(150);
                };
                break;
            case INSTALLED:
                title = R.string.local_games_component_reinstall;
                action = view -> {
                    if (showActiveInstallationConsole()) return;
                    disableActions(row);
                    String taskId = ComponentTasks.enqueue(this, item.getDescriptor().getId());
                    showComponentInstallationConsole(item.getDescriptor().getId(), taskId);
                    scheduleCatalogRefresh(150);
                };
                break;
            case QUEUED:
            case DOWNLOADING:
            case VERIFYING:
            case VERIFIED:
            case INSTALLING:
                if (task == null) {
                    hidePrimaryAction(row);
                    return;
                }
                title = R.string.local_games_component_pause;
                action = view -> {
                    disableActions(row);
                    ComponentTasks.pause(this, task.getTaskId());
                    scheduleCatalogRefresh(150);
                };
                break;
            case PAUSED:
                if (task == null) {
                    hidePrimaryAction(row);
                    return;
                }
                title = R.string.local_games_component_resume;
                action = view -> {
                    if (showActiveInstallationConsole()) return;
                    disableActions(row);
                    ComponentTasks.resume(this, task.getTaskId());
                    showComponentInstallationConsole(task);
                    scheduleCatalogRefresh(150);
                };
                break;
            case FAILED:
                if (task == null) {
                    hidePrimaryAction(row);
                    return;
                }
                title = R.string.local_games_component_retry;
                action = view -> {
                    if (showActiveInstallationConsole()) return;
                    disableActions(row);
                    ComponentTasks.retry(this, task.getTaskId());
                    showComponentInstallationConsole(task);
                    scheduleCatalogRefresh(150);
                };
                break;
            default:
                hidePrimaryAction(row);
                return;
        }
        row.localGamesComponentPrimaryAction.setText(title);
        row.localGamesComponentPrimaryAction.setOnClickListener(action);
        row.localGamesComponentPrimaryAction.setEnabled(true);
        row.localGamesComponentPrimaryAction.setVisibility(View.VISIBLE);
    }

    /** The top action remains available after a user hides the modal console. */
    private void renderActiveInstallationConsoleAction() {
        boolean active = findActiveComponentTask() != null || findActiveSetupTask() != null;
        binding.localGamesComponentTaskConsole.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    /** @return true when another installation or reset owns the single execution slot. */
    private boolean showActiveInstallationConsole() {
        ComponentTask component = findActiveComponentTask();
        if (component != null) {
            showComponentInstallationConsole(component);
            return true;
        }
        RuntimeSetupTask setup = findActiveSetupTask();
        if (setup != null) {
            RuntimeSetupConsoleDialog.show(this, setup.getTaskId());
            return true;
        }
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        for (ResetTarget target : ResetTarget.values()) {
            ResetTask reset = findActiveResetTask(paths, target);
            if (reset != null) {
                RuntimeSetupConsoleDialog.show(this, reset.getTaskId());
                return true;
            }
        }
        RootfsBackupTask backup = findActiveBackupTask(paths);
        if (backup != null) {
            showBackupConsole(backup.getTaskId(), backup.getKind() == RootfsBackupTask.Kind.BACKUP
                ? R.string.local_games_backup_create_console_title
                : R.string.local_games_backup_restore_console_title);
            return true;
        }
        return false;
    }

    @Nullable
    private RootfsBackupTask findActiveBackupTask(GameStoragePaths paths) {
        try {
            for (RootfsBackupTask task : new FileRootfsBackupTaskRepository(
                    paths.getBackupTasksDirectory()).list()) {
                if (!task.getState().isTerminal()) return task;
            }
        } catch (IOException | RuntimeException ignored) {
            // The service remains the final gate if task persistence cannot be read here.
        }
        return null;
    }

    private void showComponentInstallationConsole(ComponentTask task) {
        showComponentInstallationConsole(task.getPackageName(), task.getTaskId());
    }

    private void showComponentInstallationConsole(String componentId, String taskId) {
        if (TERMUX_GLIBC_RUNTIME_COMPONENT.equals(componentId)) {
            RuntimeSetupConsoleDialog.show(this, taskId,
                R.string.local_games_component_console_title);
        } else {
            ComponentTaskConsoleDialog.show(this, taskId);
        }
    }

    @Nullable
    private ComponentTask findActiveComponentTask() {
        try {
            ComponentStoragePaths paths = new ComponentStoragePaths(getFilesDir());
            for (ComponentTask task : new FileComponentTaskRepository(paths.getTasksDirectory()).list()) {
                if (task.getState().shouldRecoverAutomatically()) return task;
            }
        } catch (IOException | RuntimeException ignored) {
            // The service remains the final gate if task persistence cannot be read here.
        }
        return null;
    }

    @Nullable
    private RuntimeSetupTask findActiveSetupTask() {
        try {
            if (runtimeSetupTaskRepository == null) return null;
            for (RuntimeSetupTask task : runtimeSetupTaskRepository.list()) {
                if (!task.getState().isTerminal()) return task;
            }
        } catch (IOException | RuntimeException ignored) {
            // The service validates the same invariant before accepting a command.
        }
        return null;
    }

    private boolean requiresRuntimeActivation(ComponentCatalogItem item) {
        if (item.getState() != ComponentCatalogState.INSTALLED ||
            !item.getDescriptor().supportsBackend(
                com.termux.localgames.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX)) {
            return false;
        }
        if (appHost == null) return true;
        try {
            return !appHost.isRuntimeComponentAvailable(item.getDescriptor().getId(),
                item.getDescriptor().getVersion(), item.getDescriptor().getSha256());
        } catch (RuntimeException error) {
            return true;
        }
    }

    private boolean isRuntimeComponentAvailable(ComponentDescriptor descriptor) {
        if (appHost == null) return false;
        try {
            return appHost.isRuntimeComponentAvailable(descriptor.getId(),
                descriptor.getVersion(), descriptor.getSha256());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isTermuxGlibcRuntime(ComponentDescriptor descriptor) {
        return descriptor != null && TERMUX_GLIBC_RUNTIME_COMPONENT.equals(descriptor.getId());
    }

    private static void disableActions(ItemLocalGamesComponentBinding row) {
        row.localGamesComponentPrimaryAction.setEnabled(false);
    }

    private static void hidePrimaryAction(ItemLocalGamesComponentBinding row) {
        row.localGamesComponentPrimaryAction.setVisibility(View.GONE);
        row.localGamesComponentPrimaryAction.setOnClickListener(null);
    }

    private void renderCatalogError(String message) {
        binding.localGamesComponentsLoading.setVisibility(View.GONE);
        binding.localGamesComponentsError.setText(
            getString(R.string.local_games_components_load_failed, message));
        binding.localGamesComponentsError.setVisibility(View.VISIBLE);
    }

    /** Fail-fast "ToC" entry point for every "Import game" affordance on this screen -- stops
     *  the user at tap-time when a required runtime is missing, rather than after they've picked
     *  a folder and scanned it in GameImportActivity (whose own gate remains the authoritative
     *  backstop, covering GameFileManagerActivity's "import as game" action too). */
    private void startImportFlow() {
        GameImportReadinessGate.require(this, libraryExecutor, () ->
            startActivity(LocalGames.createFileManagerIntent(this)));
    }

    private void renderRuntimeStatus() {
        libraryExecutor.execute(() -> {
            GameStoragePaths paths = new GameStoragePaths(getFilesDir());
            RuntimeEnvironmentStatus status = new RuntimeEnvironmentStatus(paths);
            RuntimeReadinessState glibcState = status.glibcState();
            RuntimeReadinessState rootfsState = status.rootfsState();
            ResetTask activeGlibcReset = findActiveResetTask(paths, ResetTarget.GLIBC);
            mainHandler.post(() -> {
                if (binding == null) return;
                RuntimeReadinessState glibcVisual = activeGlibcReset != null
                    ? RuntimeReadinessState.INCOMPLETE : glibcState;
                glibcBladeBreathAnimator = applyBladeState(
                    binding.localGamesRuntimeEmblem.localGamesEmblemGlibcBlade,
                    binding.localGamesRuntimeEmblem.localGamesEmblemGlibcIcon,
                    glibcVisual, glibcBladeBreathAnimator);
                containerBladeBreathAnimator = applyBladeState(
                    binding.localGamesRuntimeEmblem.localGamesEmblemContainerBlade,
                    binding.localGamesRuntimeEmblem.localGamesEmblemContainerIcon,
                    rootfsState, containerBladeBreathAnimator);
                binding.localGamesRuntimeEmblem.localGamesEmblemGlibcTap.setOnClickListener(view -> {
                    if (glibcState == RuntimeReadinessState.NOT_READY) confirmGlibcInstall();
                    else confirmReset(ResetTarget.GLIBC, null);
                });
                binding.localGamesRuntimeEmblem.localGamesEmblemContainerTap.setOnClickListener(view -> {
                    if (rootfsState == RuntimeReadinessState.NOT_READY) confirmRootfsInstall();
                    else confirmRootfsRebuild();
                });
            });
        });
    }

    /** Triggers the same shared termux-glibc-runtime component install used by the per-game
     *  Components list, without navigating there -- reasonable since the component itself is
     *  not game-specific. */
    private void confirmGlibcInstall() {
        if (showActiveInstallationConsole()) return;
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_games_install_confirm_title)
            .setMessage(R.string.local_games_install_confirm_glibc)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_install_action, (dialog, which) ->
                installGlibcRuntime())
            .show();
    }

    private void installGlibcRuntime() {
        String taskId = ComponentTasks.enqueue(this, TERMUX_GLIBC_RUNTIME_COMPONENT);
        RuntimeSetupConsoleDialog.show(this, taskId)
            .setOnDismissListener(dialog -> renderRuntimeStatus());
        mainHandler.postDelayed(this::renderRuntimeStatus, 400);
    }

    /** Builds the shared RootFS base runtime (box64, Wine, fonts) that new game containers are
     *  extracted from -- the same setup triggered from the per-game Components
     *  list when no such container exists yet, without navigating there. */
    private void confirmRootfsInstall() {
        if (showActiveInstallationConsole()) return;
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_games_install_confirm_title)
            .setMessage(R.string.local_games_install_confirm_rootfs)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_install_action, (dialog, which) ->
                installRootfsRuntime())
            .show();
    }

    /** Deletes the current shared RootFS base archive (if any) and rebuilds it from scratch, as
     *  one user-confirmed operation -- unlike the old reset-then-separately-rebuild flow, this
     *  can never leave the base in a torn-down state the user has to remember to come back and
     *  rebuild. Offers "restore from backup" as a second, much faster starting point whenever a
     *  prior Backup actually exists on external storage (see the Settings page's "Backup current
     *  environment" action) -- both routes end at the one live shared RootFS, so either is valid. */
    private void confirmRootfsRebuild() {
        if (showActiveInstallationConsole()) return;
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        libraryExecutor.execute(() -> {
            boolean backupExists = paths.getExternalBackupFile().isFile();
            mainHandler.post(() -> {
                if (showActiveInstallationConsole()) return;
                MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.local_games_rootfs_rebuild_confirm_title)
                    .setMessage(R.string.local_games_rootfs_rebuild_confirm_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.local_games_rootfs_rebuild_action, (dialog, which) ->
                        installRootfsRuntime());
                if (backupExists) {
                    builder.setNeutralButton(R.string.local_games_backup_restore_action,
                        (dialog, which) -> confirmBackupRestore());
                }
                builder.show();
            });
        });
    }

    /** Second confirmation for restoring the external backup over the current shared RootFS --
     *  a separate step from confirmRootfsRebuild()'s own dialog since this one explains what
     *  "restore" actually does (replaces the live base + Wine prefix templates) and is also the
     *  entry point showActiveInstallationConsole() routes back to if a restore is already running. */
    private void confirmBackupRestore() {
        if (showActiveInstallationConsole()) return;
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_games_backup_restore_confirm_title)
            .setMessage(R.string.local_games_backup_restore_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_backup_restore_action, (dialog, which) ->
                ensureBackupStoragePermission(this::startBackupRestore))
            .show();
    }

    private void startBackupRestore() {
        String taskId = RootfsBackupTasks.enqueueRestore(this);
        showBackupConsole(taskId, R.string.local_games_backup_restore_console_title);
    }

    /** Settings-page entry point -- creates/overwrites the one external backup archive from the
     *  currently-published shared RootFS + Wine prefix templates. Requires the base to actually
     *  be built already; there is nothing to back up otherwise. */
    private void confirmBackupCreate() {
        if (showActiveInstallationConsole()) return;
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        libraryExecutor.execute(() -> {
            RuntimeReadinessState rootfsState = new RuntimeEnvironmentStatus(paths).rootfsState();
            boolean overwriting = paths.getExternalBackupFile().isFile();
            mainHandler.post(() -> {
                if (rootfsState != RuntimeReadinessState.READY) {
                    Toast.makeText(this, R.string.local_games_backup_requires_base,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                if (showActiveInstallationConsole()) return;
                new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.local_games_backup_create_confirm_title)
                    .setMessage(overwriting
                        ? R.string.local_games_backup_create_overwrite_message
                        : R.string.local_games_backup_create_confirm_message)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.local_games_backup_create_action, (dialog, which) ->
                        ensureBackupStoragePermission(this::startBackupCreate))
                    .show();
            });
        });
    }

    private void startBackupCreate() {
        String taskId = RootfsBackupTasks.enqueueBackup(this);
        showBackupConsole(taskId, R.string.local_games_backup_create_console_title);
    }

    /** Same live-console chrome as RuntimeSetupConsoleDialog's other uses, but reading
     *  RootfsBackupTask (no recipe/component/container, a smaller state machine, no CANCELLED)
     *  instead of RuntimeSetupTask -- see that dialog's TaskStatusLookup. */
    private void showBackupConsole(String taskId, int titleRes) {
        RuntimeSetupConsoleDialog.TaskStatusLookup lookup = id -> {
            try {
                RootfsBackupTask task = new FileRootfsBackupTaskRepository(new GameStoragePaths(
                    getFilesDir()).getBackupTasksDirectory()).find(id).orElse(null);
                if (task == null) return null;
                return new RuntimeSetupConsoleDialog.TaskStatusLookup.Status(
                    task.getState() == RootfsBackupTaskState.FAILED, false, task.getState().name());
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        };
        RuntimeSetupConsoleDialog.show(this, taskId, titleRes, lookup)
            .setOnDismissListener(dialog -> renderRuntimeStatus());
    }

    private void installRootfsRuntime() {
        // This blade is reachable with no specific game in context (componentContainerId may be
        // unset), but RuntimeSetupTasks.enqueueBaseOnly requires a real, non-DEFAULT_ID
        // containerId even though a BASE_ONLY build never creates a container under it. Use a
        // disposable id instead of forcing a game context here.
        String taskId = RuntimeSetupTasks.enqueueBaseOnly(this, "debian-13-games-rootfs",
            "shared-rebuild-" + UUID.randomUUID());
        RuntimeSetupConsoleDialog.show(this, taskId)
            .setOnDismissListener(dialog -> renderRuntimeStatus());
        mainHandler.postDelayed(this::renderRuntimeStatus, 400);
    }

    /** Marks a Library card as "container build in progress" with a red/breathing background
     *  (mirrors {@code applyBladeState}'s breathing treatment, applied to the card background
     *  instead of an icon's alpha), or restores the normal surface color when not building.
     *  Takes the row's root {@code View} rather than assuming {@code MaterialCardView} --
     *  the landscape variant (layout-land/item_local_game.xml) roots a plain LinearLayout with
     *  the MaterialCardView only wrapping the cover image, so a plain CardView cast/property
     *  crashes there (confirmed on device). Animate the CardView-specific "cardBackgroundColor"
     *  property when the root really is one (portrait, correct rounded-corner fill), otherwise
     *  fall back to the generic View "backgroundColor" property (landscape). Started animators
     *  are tracked in {@code libraryCardBreathAnimators} so the next {@code renderLibrary()}
     *  pass can cancel them before the row views are discarded. */
    private void applyCardBuildingState(View card, boolean building) {
        String property = card instanceof com.google.android.material.card.MaterialCardView
            ? "cardBackgroundColor" : "backgroundColor";
        int surfaceColor = ContextCompat.getColor(this, R.color.local_games_surface);
        if (!building) {
            if ("cardBackgroundColor".equals(property)) {
                ((com.google.android.material.card.MaterialCardView) card)
                    .setCardBackgroundColor(surfaceColor);
            } else {
                card.setBackgroundColor(surfaceColor);
            }
            return;
        }
        int errorColor = ContextCompat.getColor(this, R.color.local_games_error);
        android.animation.ObjectAnimator animator = android.animation.ObjectAnimator.ofArgb(
            card, property, surfaceColor, errorColor);
        animator.setDuration(1100);
        animator.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
        animator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animator.start();
        libraryCardBreathAnimators.add(animator);
    }

    /** Colors one blade + its icon for the given readiness state and starts/stops the
     *  breathing alpha animation that flags "not settled yet". Returns the animator now
     *  running on {@code blade}, or null if it is static (READY). */
    @Nullable
    private android.animation.ObjectAnimator applyBladeState(android.widget.ImageView blade,
        android.widget.ImageView icon, RuntimeReadinessState state,
        @Nullable android.animation.ObjectAnimator existingAnimator) {
        if (existingAnimator != null) existingAnimator.cancel();
        blade.setAlpha(1f);
        int bladeColorRes;
        int iconColorRes;
        boolean breathing;
        switch (state) {
            case NOT_READY:
            case INCOMPLETE:
                // Both "nothing built yet" and "built but not fully settled" read the same
                // visually (red + breathing) -- a separate amber accent for INCOMPLETE wasn't
                // worth the extra color to distinguish.
                bladeColorRes = R.color.local_games_error;
                iconColorRes = R.color.local_games_emblem_icon_dark;
                breathing = true;
                break;
            default:
                bladeColorRes = R.color.local_games_emblem_neutral;
                iconColorRes = R.color.local_games_on_surface;
                breathing = false;
                break;
        }
        blade.setImageTintList(android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, bladeColorRes)));
        icon.setImageTintList(android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(this, iconColorRes)));
        if (!breathing) return null;
        android.animation.ObjectAnimator animator = android.animation.ObjectAnimator.ofFloat(
            blade, View.ALPHA, 1f, 0.5f);
        animator.setDuration(1100);
        animator.setRepeatMode(android.animation.ObjectAnimator.REVERSE);
        animator.setRepeatCount(android.animation.ObjectAnimator.INFINITE);
        animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animator.start();
        return animator;
    }

    @Nullable
    private ResetTask findActiveResetTask(GameStoragePaths paths, ResetTarget target) {
        try {
            for (ResetTask task : new FileResetTaskRepository(paths.getResetTasksDirectory())
                .list()) {
                if (task.getTarget() == target && !task.getState().isTerminal()) return task;
            }
        } catch (IOException | RuntimeException ignored) {
            // The service remains the final gate if task persistence cannot be read here.
        }
        return null;
    }

    private void confirmReset(ResetTarget target, @Nullable String resetKey) {
        if (showActiveInstallationConsole()) return;
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_games_reset_confirm_title)
            .setMessage(R.string.local_games_reset_confirm_glibc)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_reset_action, (dialog, which) -> {
                String taskId = ResetTasks.enqueue(this, target, resetKey);
                RuntimeSetupConsoleDialog.show(this, taskId)
                    .setOnDismissListener(consoleDialog -> renderRuntimeStatus());
                mainHandler.postDelayed(this::renderRuntimeStatus, 400);
            })
            .show();
    }

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        catalogExecutor.shutdownNow();
        libraryExecutor.shutdownNow();
        if (glibcBladeBreathAnimator != null) glibcBladeBreathAnimator.cancel();
        if (containerBladeBreathAnimator != null) containerBladeBreathAnimator.cancel();
        binding = null;
        super.onDestroy();
    }
}
