package com.termux.ui.activity;

import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.shared.android.PermissionUtils;
import com.termux.ui.R;
import com.termux.ui.api.AppExperienceMode;
import com.termux.ui.api.ComponentTasks;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.data.SettingsPage;
import com.termux.ui.platform.UiThemePreference;
import com.termux.ui.databinding.ActivityLocalGamesBinding;
import com.termux.ui.databinding.DialogGameActionsBinding;
import com.termux.ui.databinding.DialogGameLaunchBinding;
import com.termux.ui.databinding.ItemLocalGameBinding;
import com.termux.ui.databinding.ItemLocalGameImportBinding;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.platform.OrientationPreference;
import com.termux.ui.runtime.RuntimeBackupSharedStorage;
import com.termux.ui.wine.activity.GameRuntimeOptionsView;
import com.termux.ui.wine.activity.RuntimeBackupFlow;
import com.termux.ui.wine.api.PrefixSetupTasks;
import com.termux.ui.wine.api.ResetTasks;
import com.termux.ui.wine.api.RootfsBackupTasks;
import com.termux.ui.wine.api.RuntimeSetupTasks;
import com.termux.ui.wine.artwork.GameArtworkLoader;
import com.termux.ui.wine.artwork.GameArtworkStore;
import com.termux.ui.wine.data.FileGameRepository;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.data.FileResetTaskRepository;
import com.termux.ui.wine.data.FileRootfsBackupTaskRepository;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.data.GameAccessState;
import com.termux.ui.wine.data.GameLibraryItem;
import com.termux.ui.wine.data.GameLibraryRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.domain.ResetTarget;
import com.termux.ui.wine.domain.ResetTask;
import com.termux.ui.wine.domain.RootfsBackupTask;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeReadinessState;
import com.termux.ui.wine.domain.RuntimeSetupTask;
import com.termux.ui.wine.importer.SafGameAccessProbe;
import com.termux.ui.wine.recovery.GameOrphanSweeper;
import com.termux.ui.wine.recovery.GameUninstallPlan;
import com.termux.ui.wine.recovery.GameUninstaller;
import com.termux.ui.wine.runtime.RuntimeEnvironmentStatus;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root Activity owned by the standalone games feature module. */
public final class LocalGamesActivity extends AppUiActivity {

    public static final String EXTRA_CORE_BACKEND = "com.termux.ui.extra.CORE_BACKEND";
    private static final String INDEX_ASSET = "termux-app-packages/index-v1.json";
    private static final String TERMUX_GLIBC_ID = "termux-glibc";
    private static final String STATE_SELECTED_TAB = "local_games.selected_tab";
    private static final String STATE_SETTINGS_PAGE = "local_games.settings_page";
    private boolean navigationUpdating;
    private UiNavigation navigation;
    private CollectionToolbar collectionToolbar;
    private static final int TAB_LIBRARY = 0;
    private static final int TAB_SETTINGS = 2;
    private static final int GAME_ACTION_ENGINE = 0;
    private static final int GAME_ACTION_EDIT = 1;
    private static final int GAME_ACTION_STORAGE = 2;
    private static final int GAME_ACTION_CDRIVE_BACKUP = 6;
    private com.termux.ui.wine.activity.WineCDriveBackupFlow cdriveBackupFlow;
    private static final int GAME_ACTION_REAUTHORIZE = 4;
    private static final int GAME_ACTION_REMOVE = 5;
    /** The launch sheet is a compact card; wider windows do not stretch it. */
    private static final int LAUNCH_SHEET_MAX_WIDTH_DP = 440;
    // External storage (MANAGE_EXTERNAL_STORAGE on API 30+) is requested lazily, only the first
    // time the user actually touches Backup/Restore -- not at app startup -- since nothing else
    // on this screen needs it. The request can route through a system Settings screen (API 30+)
    // or a runtime permission dialog (below API 30), both of which return asynchronously via the
    // overrides below; pendingBackupStorageAction is what to resume once that result arrives.
    private static final int REQUEST_BACKUP_STORAGE_PERMISSION = 8401;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService libraryExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesLibrary"));
    private final AtomicBoolean libraryLoadInFlight = new AtomicBoolean();

    private ActivityLocalGamesBinding binding;
    private FileRuntimeSetupTaskRepository runtimeSetupTaskRepository;
    private GameLibraryRepository libraryRepository;
    private GameArtworkLoader artworkLoader;
    private LocalGamesHost appHost;
    private boolean started;
    private int selectedTab;
    private int libraryGeneration;
    private int libraryColumns = 2;
    /** The items and grid width of the last render, so the grid re-flows when its width changes
     *  (rotation, split-screen resize) without reloading the data. */
    @Nullable private List<GameLibraryItem> renderedLibraryItems;
    private int renderedGridWidth;
    @Nullable private PopupWindow moreMenuPopup;
    private Runnable pendingBackupStorageAction;
    private Map<String, BuildingTask> buildingGameTasks = Collections.emptyMap();
    private Map<String, GameRuntimeBackendType> libraryBackends = Collections.emptyMap();
    private Map<String, String> libraryWines = Collections.emptyMap();
    private Map<String, String> libraryProfileErrors = Collections.emptyMap();
    private static final long BUILDING_POLL_MS = 2500L;
    private final Runnable buildingPoll = () -> loadLibraryAsync(true);
    private final List<android.animation.ObjectAnimator> libraryCardBreathAnimators =
        new ArrayList<>();
    /** Which menu level the Settings page shows, and the environment states its rows display
     *  (null until the first background check has finished). */
    private SettingsPage settingsPage = SettingsPage.ROOT;
    @Nullable private RuntimeReadinessState glibcStatus;
    @Nullable private RuntimeReadinessState rootfsStatus;
    private OnBackPressedCallback settingsBackCallback;
    private final BackExitConfirmation exitConfirmation = new BackExitConfirmation();
    @Nullable private Toast exitToast;
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
        navigation = UiNavigation.install(this, R.id.local_games_navigation_library,
            this::onNavigationDestination);
        collectionToolbar = new CollectionToolbar(this, binding.localGamesLibraryToolbar,
            "wine", R.string.local_games_title, R.string.local_games_import_hero_message,
            this::loadLibraryAsync, () -> {
                if (renderedLibraryItems != null) populateLibraryGrid(renderedLibraryItems, libraryGeneration);
            });
        collectionToolbar.enableRuntimeSelector(() -> {
            if (renderedLibraryItems != null) populateLibraryGrid(renderedLibraryItems, libraryGeneration);
        });
        selectedTab = savedInstanceState == null ? TAB_LIBRARY
            : savedInstanceState.getInt(STATE_SELECTED_TAB, TAB_LIBRARY);
        // Applied after the initial showPage() below: selecting the library item first runs
        // showPage(TAB_LIBRARY), which resets the Settings level.
        SettingsPage restoredSettingsPage = savedInstanceState == null ? SettingsPage.ROOT
            : SettingsPage.fromOrdinal(savedInstanceState.getInt(STATE_SETTINGS_PAGE, 0));
        binding.localGamesToolbar.setNavigationOnClickListener(view -> {
            navigateBackOrConfirmExit();
        });
        binding.localGamesToolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.local_games_action_help) return false;
            if (selectedTab == TAB_SETTINGS) {
                GamesHelpDialog.show(this, R.string.local_games_settings_title,
                    R.string.local_games_settings_help);
            } else {
                GamesHelpDialog.show(this, R.string.local_games_title,
                    R.string.local_games_import_hero_message);
            }
            return true;
        });
        // Sub-pages navigate upwards. The UI home requires two consecutive Back presses to exit.
        settingsBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                navigateBackOrConfirmExit();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, settingsBackCallback);
        // The grid re-flows when its width changes (rotation, split-screen).
        binding.localGamesLibraryItems.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                                   oldLeft, oldTop, oldRight,
                                                                   oldBottom) -> {
            int width = right - left;
            if (renderedLibraryItems != null && width > 0 && width != renderedGridWidth) {
                view.post(() -> {
                    if (binding != null && renderedLibraryItems != null &&
                        view.getWidth() != renderedGridWidth) {
                        populateLibraryGrid(renderedLibraryItems, libraryGeneration);
                    }
                });
            }
        });
        initializeAppExperience();

        runtimeSetupTaskRepository = new FileRuntimeSetupTaskRepository(
            new GameStoragePaths(getFilesDir()).getRuntimeSetupTasksDirectory());
        initializeLibrary();
        // The Import card is always the last grid item: it is there from the first frame, before
        // (and independent of) any library load.
        populateLibraryGrid(Collections.emptyList(), libraryGeneration);
        ComponentTasks.reconcile(this);
        RuntimeSetupTasks.reconcileAll(this);
        PrefixSetupTasks.reconcileAll(this);
        if (getIntent().hasExtra(UiNavigation.EXTRA_DESTINATION)) {
            selectedTab = getIntent().getIntExtra(UiNavigation.EXTRA_DESTINATION, 0)
                == R.id.local_games_navigation_settings ? TAB_SETTINGS : TAB_LIBRARY;
            getIntent().removeExtra(UiNavigation.EXTRA_DESTINATION);
        }
        int initialPage = selectedTab;
        syncNavigationSelection(R.id.local_games_navigation_library);
        showPage(initialPage);
        if (initialPage == TAB_SETTINGS && !restoredSettingsPage.isRoot()) {
            openSettingsPage(restoredSettingsPage);
        }
        consumeCoreComponentsIntent();
        ResetTasks.reconcileAll(this);
        RootfsBackupTasks.reconcileAll(this);
        RuntimeBackupFlow.instance().attach(this, this::renderRuntimeStatus);
        cdriveBackupFlow = new com.termux.ui.wine.activity.WineCDriveBackupFlow(this);
        cdriveBackupFlow.resume();
        renderRuntimeStatus();
        mainHandler.post(this::consumePendingSetupConsoleIntent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasExtra(UiNavigation.EXTRA_DESTINATION)) {
            int destination = intent.getIntExtra(UiNavigation.EXTRA_DESTINATION, 0);
            intent.removeExtra(UiNavigation.EXTRA_DESTINATION);
            showPage(destination == R.id.local_games_navigation_settings ? TAB_SETTINGS : TAB_LIBRARY);
        }
        consumeCoreComponentsIntent();
        mainHandler.post(this::consumePendingSetupConsoleIntent);
    }

    /** Opens the environment actions without building or installing anything. */
    private void consumeCoreComponentsIntent() {
        String backend = getIntent().getStringExtra(EXTRA_CORE_BACKEND);
        if (backend == null) return;
        getIntent().removeExtra(EXTRA_CORE_BACKEND);
        showPage(TAB_SETTINGS);
        openSettingsPage(GameRuntimeBackendType.fromStorageValue(backend) == GameRuntimeBackendType.ROOTFS_PROOT
            ? SettingsPage.PROOT : SettingsPage.NATIVE);
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
        if (selectedTab == TAB_LIBRARY) loadLibraryAsync();
        else renderAppExperience();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A reset runs behind RuntimeSetupConsoleDialog and control returns here once it
        // finishes -- re-check both environments so the Settings entries show the new state.
        renderRuntimeStatus();
    }

    @Override
    protected void onStop() {
        resetExitConfirmation();
        started = false;
        mainHandler.removeCallbacks(buildingPoll);
        dismissMoreMenu();
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
        outState.putInt(STATE_SETTINGS_PAGE, settingsPage.ordinal());
        super.onSaveInstanceState(outState);
    }

    private void initializeLibrary() {
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        libraryRepository = new GameLibraryRepository(
            new FileGameRepository(paths.getLibraryDirectory()),
            new SafGameAccessProbe(getContentResolver()));
        artworkLoader = new GameArtworkLoader(new GameArtworkStore(getFilesDir()));
    }

    private void showPage(int tab) {
        resetExitConfirmation();
        selectedTab = tab == TAB_SETTINGS ? TAB_SETTINGS : TAB_LIBRARY;
        if (selectedTab == TAB_LIBRARY) settingsPage = SettingsPage.ROOT;
        boolean showSettings = selectedTab == TAB_SETTINGS;
        binding.localGamesLibraryPage.setVisibility(showSettings ? View.GONE : View.VISIBLE);
        binding.localGamesSettingsPage.setVisibility(showSettings ? View.VISIBLE : View.GONE);
        updateChrome();
        updateSettingsBackCallback();
        if (showSettings) renderAppExperience();
        else if (started) loadLibraryAsync();
    }

    private void updateChrome() {
        navigation.setVisible(true);
        navigation.setSelected(selectedTab == TAB_SETTINGS
            ? R.id.local_games_navigation_settings : R.id.local_games_navigation_library);
        navigation.updateConfiguration();
        binding.localGamesToolbar.setVisibility(selectedTab == TAB_LIBRARY ? View.GONE : View.VISIBLE);
        binding.localGamesLibraryToolbar.setVisibility(selectedTab == TAB_LIBRARY ? View.VISIBLE : View.GONE);
        if (selectedTab == TAB_SETTINGS) {
            if (settingsPage.isRoot()) {
                binding.localGamesToolbar.setNavigationIcon(null);
            } else {
                binding.localGamesToolbar.setNavigationIcon(R.drawable.local_games_ic_back);
                binding.localGamesToolbar.setNavigationIconTint(
                    ContextCompat.getColor(this, R.color.local_games_on_surface));
                binding.localGamesToolbar.setNavigationContentDescription(
                    androidx.appcompat.R.string.abc_action_bar_up_description);
            }
            binding.localGamesToolbar.setTitle(null);
        } else {
            binding.localGamesToolbar.setTitle(R.string.local_games_title);
        }
    }

    /** Root pages share navigation; this Activity owns the Wine and Settings content. */
    private boolean onNavigationDestination(int itemId) {
        // Programmatic syncs (showPage) must not re-enter navigation.
        if (navigationUpdating) return true;
        if (itemId != R.id.local_games_navigation_library &&
            itemId != R.id.local_games_navigation_settings) {
            UiNavigation.openDestination(this, itemId);
            return false;
        }
        boolean settings = itemId == R.id.local_games_navigation_settings;
        // Tapping the tab that is already showing does nothing, like re-selecting a bottom bar item.
        if (selectedTab == (settings ? TAB_SETTINGS : TAB_LIBRARY)) return true;
        navigationUpdating = true;
        try {
            showPage(settings ? TAB_SETTINGS : TAB_LIBRARY);
            navigation.animateContentChange();
        } finally {
            navigationUpdating = false;
        }
        return true;
    }

    private void syncNavigationSelection(int menuItemId) {
        navigation.setSelected(menuItemId);
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The Activity is not recreated on rotation: tab, Settings level, open dialogs and the
        // scroll positions stay as they are; only the orientation-dependent chrome is re-laid out.
        dismissMoreMenu();
        if (binding == null) return;
        updateChrome();
    }

    private void initializeAppExperience() {
        try {
            appHost = LocalGames.requireHost(this);
        } catch (RuntimeException error) {
            appHost = null;
        }
        renderAppExperience();
        updateChrome();
    }

    /** The default mode is shown on the Settings menu; re-render it from the host's current mode. */
    private void renderAppExperience() {
        renderSettingsMenu();
    }

    private void confirmAppExperienceSwitch(AppExperienceMode target) {
        if (appHost == null || target == appHost.getAppExperienceMode()) {
            renderAppExperience();
            return;
        }
        new FrostedDialogBuilder(this)
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
        loadLibraryAsync(false);
    }

    /** A card is shown as "building" only for the pass that found its setup task running, and
     *  nothing else re-renders the library when that task ends in the background. While any card
     *  is building, re-check every few seconds ({@code pollOnly}: re-render only if the set of
     *  building games changed, so the breathing animation is not restarted needlessly). */
    private void loadLibraryAsync(boolean pollOnly) {
        mainHandler.removeCallbacks(buildingPoll);
        if (!started || selectedTab != TAB_LIBRARY ||
            !libraryLoadInFlight.compareAndSet(false, true)) return;
        int generation = ++libraryGeneration;
        if (!pollOnly) binding.localGamesLibraryLoading.show();
        binding.localGamesLibraryError.setVisibility(View.GONE);
        libraryExecutor.execute(() -> {
            List<GameLibraryItem> items = null;
            Map<String, BuildingTask> building = new HashMap<>();
            Map<String, GameRuntimeBackendType> backends = new HashMap<>();
            Map<String, String> wines = new HashMap<>();
            Map<String, String> profileErrors = new HashMap<>();
            String error = null;
            try {
                // Containers and profiles that removed games or abandoned edits left behind are reclaimed
                // here; a failed sweep never keeps the library from loading.
                if (!pollOnly) {
                    try {
                        GameOrphanSweeper.sweep(getFilesDir());
                    } catch (IOException | RuntimeException sweepError) {
                        Log.w("LocalGamesActivity", "orphan sweep failed", sweepError);
                    }
                }
                items = libraryRepository.load();
                GameStoragePaths gamePaths = new GameStoragePaths(getFilesDir());
                FileRuntimeProfileRepository profiles =
                    new FileRuntimeProfileRepository(gamePaths.getProfilesDirectory());
                ComponentIndex wineIndex;
                try (InputStream input = getAssets().open(INDEX_ASSET)) {
                    wineIndex = new ComponentIndexParser().parse(input);
                }
                List<CustomRuntimeComponent> customWines = new com.termux.ui.data.FileCustomRuntimeComponentRepository(
                    gamePaths.getCustomRuntimeComponentsDirectory()).list();
                List<com.termux.ui.api.RuntimeComponentVariant> installedWines = new ArrayList<>();
                java.util.Set<GameRuntimeBackendType> loadedWineBackends = new java.util.HashSet<>();
                for (GameLibraryItem item : items) {
                    String gameId = item.getGame().getId();
                    RuntimeProfile savedProfile;
                    try {
                        savedProfile = profiles.find(gameId).orElseThrow(() ->
                            new IOException("game_runtime_profile_missing:" + gameId));
                    } catch (IOException invalidProfile) {
                        profileErrors.put(gameId, safeMessage(invalidProfile));
                        try {
                            profiles.findBinding(gameId).ifPresent(binding ->
                                backends.put(gameId, binding.getRuntimeBackendType()));
                        } catch (IOException unreadableBinding) {
                            Log.w("LocalGamesActivity", "profile ownership unavailable: " + gameId, unreadableBinding);
                        }
                        continue;
                    }
                    if (savedProfile != null && loadedWineBackends.add(savedProfile.getRuntimeBackendType())) {
                        installedWines.addAll(LocalGames.requireHost(this).listInstalledRuntimeComponentVariants(
                            CustomRuntimeComponent.Kind.WINE, savedProfile.getRuntimeBackendType()));
                    }
                    if (savedProfile != null) {
                        RuntimeProfile profile = savedProfile;
                        backends.put(item.getGame().getId(), profile.getRuntimeBackendType());
                        wines.put(item.getGame().getId(), com.termux.ui.wine.runtime.WineDisplayNames.resolve(
                            profile, customWines, installedWines, wineIndex));
                        com.termux.ui.wine.api.ActiveWineInstallation installation =
                            com.termux.ui.wine.api.ActiveWineInstallation.find(getFilesDir(), item.getGame().getId());
                        if (installation != null) building.put(item.getGame().getId(),
                            new BuildingTask(installation.taskId, installation.rootfs));
                    }
                    if (!backends.containsKey(item.getGame().getId())) {
                        throw new IOException("game_runtime_profile_missing:" + item.getGame().getId());
                    }
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
                    boolean unchanged = pollOnly && sameBuilding(buildingGameTasks, loadedBuilding)
                        && libraryWines.equals(wines) && libraryBackends.equals(backends)
                        && libraryProfileErrors.equals(profileErrors);
                    buildingGameTasks = loadedBuilding;
                    libraryBackends = backends;
                    libraryWines = wines;
                    libraryProfileErrors = profileErrors;
                    if (!unchanged) renderLibrary(loaded, generation);
                    else binding.localGamesLibraryLoading.hide();
                    if (!loadedBuilding.isEmpty()) {
                        mainHandler.postDelayed(buildingPoll, BUILDING_POLL_MS);
                    }
                } else renderLibraryError(failure);
            });
        });
    }

    private static boolean sameBuilding(Map<String, BuildingTask> before,
                                        Map<String, BuildingTask> after) {
        if (before.size() != after.size()) return false;
        for (Map.Entry<String, BuildingTask> entry : before.entrySet()) {
            BuildingTask other = after.get(entry.getKey());
            if (other == null || !other.taskId.equals(entry.getValue().taskId)) return false;
        }
        return true;
    }

    private void renderLibrary(List<GameLibraryItem> items, int generation) {
        binding.localGamesLibraryLoading.hide();
        binding.localGamesLibraryError.setVisibility(View.GONE);
        populateLibraryGrid(items, generation);
    }

    /** Rebuilds the grid: the game cards in order, then the Import card as the last item,
     *  unconditionally (no games yet, loading, load failure or a full library alike). Every
     *  render, poll refresh and rotation reflow goes through here, so the Import card always
     *  follows the last game card in the freshly computed columns. */
    private void populateLibraryGrid(List<GameLibraryItem> items, int generation) {
        binding.localGamesLibraryItems.removeAllViews();
        // Rows are fully reinflated on every pass -- cancel animators from the previous pass so
        // they do not keep running (and ticking the UI thread) against now-detached views.
        for (android.animation.ObjectAnimator animator : libraryCardBreathAnimators) {
            animator.cancel();
        }
        libraryCardBreathAnimators.clear();
        int gridWidth = libraryGridWidth();
        boolean landscape = getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        renderedLibraryItems = items;
        GameRuntimeBackendType backend = collectionToolbar.isProot()
            ? GameRuntimeBackendType.ROOTFS_PROOT : GameRuntimeBackendType.GLIBC_TERMUX_BOX;
        List<GameLibraryItem> visibleItems = new ArrayList<>();
        for (GameLibraryItem item : items) {
            String id = item.getGame().getId();
            if (libraryBackends.get(id) == backend ||
                (libraryProfileErrors.containsKey(id) && !libraryBackends.containsKey(id))) visibleItems.add(item);
        }
        binding.localGamesCount.setText(collectionToolbar.getRuntimeCountText(visibleItems.size()));
        renderedGridWidth = gridWidth;
        libraryColumns = collectionToolbar.isList() ? 1 : LibraryGridColumns.columnsFor(
            Math.round(gridWidth / getResources().getDisplayMetrics().density), landscape);
        binding.localGamesLibraryItems.setColumnCount(libraryColumns);
        for (int index = 0; index < visibleItems.size(); index++) {
            GameLibraryItem item = visibleItems.get(index);
            ItemLocalGameBinding row = ItemLocalGameBinding.bind(getLayoutInflater().inflate(
                collectionToolbar.isList() ? R.layout.item_local_game_list : R.layout.item_local_game,
                binding.localGamesLibraryItems, false));
            applyGameCardLayout(row.getRoot(), index);
            renderGameRow(row, item, generation);
            binding.localGamesLibraryItems.addView(row.getRoot());
        }
        ItemLocalGameImportBinding importCard = ItemLocalGameImportBinding.bind(getLayoutInflater().inflate(
            collectionToolbar.isList() ? R.layout.item_local_game_import_list : R.layout.item_local_game_import,
            binding.localGamesLibraryItems, false));
        applyGameCardLayout(importCard.getRoot(), LibraryGridColumns.importCardIndex(visibleItems.size()),
            true);
        importCard.getRoot().setOnClickListener(view -> startImportFlow());
        binding.localGamesLibraryItems.addView(importCard.getRoot());
    }

    /** The width the grid has (or is about to have): its laid-out width, or, before the first
     *  layout pass, the window width minus the page padding. */
    private int libraryGridWidth() {
        int laidOut = binding.localGamesLibraryItems.getWidth();
        if (laidOut > 0) return laidOut;
        int padding = 2 * getResources().getDimensionPixelSize(
            R.dimen.local_games_page_horizontal_padding);
        return Math.max(dp(140), getResources().getDisplayMetrics().widthPixels - padding);
    }

    private void applyGameCardLayout(View card, int index) {
        applyGameCardLayout(card, index, false);
    }

    /** Places a card in its grid cell. The Import card ({@code fillRow}) stretches to the tallest
     *  card of its row; game cards keep their own height. */
    private void applyGameCardLayout(View card, int index, boolean fillRow) {
        int gridWidth = renderedGridWidth > 0 ? renderedGridWidth : libraryGridWidth();
        GridLayout.LayoutParams params = new GridLayout.LayoutParams();
        params.width = LibraryGridColumns.cardWidthPx(gridWidth, libraryColumns, dp(4),
            getResources().getDisplayMetrics().density);
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.columnSpec = GridLayout.spec(LibraryGridColumns.columnOf(index, libraryColumns));
        int row = LibraryGridColumns.rowOf(index, libraryColumns);
        params.rowSpec = fillRow ? GridLayout.spec(row, GridLayout.FILL) : GridLayout.spec(row);
        params.setMargins(dp(2), dp(2), dp(2), dp(6));
        card.setLayoutParams(params);
    }

    private void renderGameRow(ItemLocalGameBinding row, GameLibraryItem item, int generation) {
        Game game = item.getGame();
        row.localGameName.setText(game.getName());
        GameRuntimeBackendType backend = libraryBackends.get(game.getId());
        String runtime = getString(backend == null ? R.string.local_game_configuration_unconfigured
            : backend == GameRuntimeBackendType.ROOTFS_PROOT
                ? R.string.local_game_runtime_profile_backend_rootfs
                : R.string.local_game_runtime_profile_backend_glibc);
        row.localGameExecutable.setText(getString(R.string.local_game_configuration_executable,
            runtime, game.getExecutable()));
        String profileError = libraryProfileErrors.get(game.getId());
        row.localGameWine.setText(profileError == null ? getString(R.string.local_game_card_wine,
            libraryWines.get(game.getId())) : getString(R.string.local_game_profile_invalid));
        row.localGameAccessStatus.setText(profileError == null ? getString(accessLabel(item.getAccessState()))
            : getString(R.string.local_game_profile_invalid_action));
        row.localGameAccessStatus.setTextColor(ContextCompat.getColor(this,
            profileError == null && item.getAccessState() == GameAccessState.ACCESSIBLE
                ? R.color.local_games_success_text : R.color.local_games_error));
        row.localGameLastPlayed.setText(game.getLastPlayedAt() == 0
            ? getString(R.string.local_game_never_played)
            : getString(R.string.local_game_last_played, DateUtils.formatDateTime(this,
                game.getLastPlayedAt(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME)));
        BuildingTask building = profileError == null ? buildingGameTasks.get(game.getId()) : null;
        applyCardBuildingState(row.getRoot(), building != null);
        row.getRoot().setOnClickListener(view -> {
            if (profileError != null) {
                showInvalidProfile(game, profileError);
                return;
            }
            withGameInstallation(game.getId(), () -> showLaunchPresentation(item));
        });
        row.getRoot().setFocusable(true);
        row.localGameDetails.setEnabled(profileError == null);
        row.localGameDetails.setOnClickListener(view -> {
            if (!libraryProfileErrors.containsKey(game.getId()))
                withGameInstallation(game.getId(), () ->
                    startActivity(LocalGames.createRuntimeOptionsIntent(this, game.getId())));
        });
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
                ? R.color.local_games_success_text : R.color.local_games_error));
        content.gameLaunchSheetStart.setEnabled(
            item.getAccessState() == GameAccessState.ACCESSIBLE);

        Dialog dialog = createPresentationDialog(content.getRoot(), LAUNCH_SHEET_MAX_WIDTH_DP);
        content.gameLaunchSheetClose.setOnClickListener(view -> dialog.dismiss());
        content.gameLaunchSheetStart.setOnClickListener(view -> {
            dialog.dismiss();
            startActivity(LocalGames.createGameLaunchIntent(this, game.getId()));
        });
        content.gameLaunchSheetDesktop.setEnabled(
            item.getAccessState() == GameAccessState.ACCESSIBLE);
        content.gameLaunchSheetDesktop.setOnClickListener(view -> {
            dialog.dismiss();
            startActivity(LocalGames.createGameDesktopLaunchIntent(this, game.getId()));
        });
        content.gameLaunchSheetLayout.setOnClickListener(view -> {
            dialog.dismiss();
            showActionsPresentation(item);
        });
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
        Dialog dialog = createPresentationDialog(content.getRoot(), 0);
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
            performGameAction(item, GAME_ACTION_STORAGE);
        });
        if (libraryBackends.get(game.getId()) == GameRuntimeBackendType.ROOTFS_PROOT) {
            content.gameActionsBackup.setOnClickListener(view -> {
                dialog.dismiss();
                performGameAction(item, GAME_ACTION_CDRIVE_BACKUP);
            });
        } else {
            ((ViewGroup) content.gameActionsBackup.getParent()).removeView(content.gameActionsBackup);
        }
        boolean reauthorize = item.getAccessState() != GameAccessState.ACCESSIBLE;
        if (reauthorize) {
            content.gameActionsReauthorize.setOnClickListener(view -> {
                dialog.dismiss();
                performGameAction(item, GAME_ACTION_REAUTHORIZE);
            });
        } else {
            // Remove unused GridLayout cells so the remaining actions stay adjacent.
            ((ViewGroup) content.gameActionsReauthorize.getParent())
                .removeView(content.gameActionsReauthorize);
        }
        content.gameActionsRemove.setOnClickListener(view -> {
            dialog.dismiss();
            performGameAction(item, GAME_ACTION_REMOVE);
        });
        dialog.show();
    }

    /** Card overflow is intentionally text-only; the launch-sheet shortcut uses the icon grid. */
    private void showActionsTextMenu(GameLibraryItem item, View anchor) {
        dismissMoreMenu();
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(8), dp(8), dp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundResource(R.drawable.local_games_bg_dialog);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        int width = textPopupWidth();
        // The popup is bounded to the window and scrolls, so a short (landscape) window never
        // pushes rows off screen; its position is clamped into the window as well.
        PopupWindow popup = new PopupWindow(scroll, width,
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(3));
        if (!libraryProfileErrors.containsKey(item.getGame().getId()))
            addTextPopupAction(content, popup, item, R.string.local_game_action_edit, GAME_ACTION_EDIT);
        addTextPopupAction(content, popup, item, R.string.local_game_action_remove,
            GAME_ACTION_REMOVE);
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        View windowRoot = anchor.getRootView();
        float density = getResources().getDisplayMetrics().density;
        int height = Math.min(content.getMeasuredHeight(),
            DialogSizing.maxHeightPx(windowRoot.getHeight(), density));
        popup.setHeight(height);
        int[] anchorLocation = new int[2];
        anchor.getLocationInWindow(anchorLocation);
        int margin = dp(8);
        int left = DialogSizing.popupLeftPx(anchorLocation[0] + anchor.getWidth(), width,
            windowRoot.getWidth(), margin);
        int top = DialogSizing.popupTopPx(anchorLocation[1],
            anchorLocation[1] + anchor.getHeight(), height, windowRoot.getHeight(), margin);
        moreMenuPopup = popup;
        popup.setOnDismissListener(() -> {
            if (moreMenuPopup == popup) moreMenuPopup = null;
        });
        popup.showAtLocation(windowRoot, Gravity.TOP | Gravity.START, left, top);
    }

    private void dismissMoreMenu() {
        PopupWindow popup = moreMenuPopup;
        moreMenuPopup = null;
        if (popup != null) popup.dismiss();
    }

    private void addTextPopupAction(LinearLayout parent, PopupWindow popup,
                                    GameLibraryItem item, int label, int action) {
        TextView row = new TextView(this);
        row.setText(label);
        row.setTextColor(ContextCompat.getColor(this, action == GAME_ACTION_REMOVE
            ? R.color.local_games_error : R.color.local_games_on_surface));
        row.setTextSize(16);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setIncludeFontPadding(false);
        row.setSingleLine(true);
        row.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.setPadding(dp(16), 0, dp(16), 0);
        row.setOnClickListener(view -> {
            popup.dismiss();
            performGameAction(item, action);
        });
        // 48 dp touch target (R.dimen.local_games_menu_row_min_height).
        parent.addView(row, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            getResources().getDimensionPixelSize(R.dimen.local_games_menu_row_min_height)));
    }

    private int textPopupWidth() {
        int[] labels = { R.string.local_game_action_edit, R.string.local_game_action_remove };
        android.text.TextPaint paint = new android.text.TextPaint();
        paint.setTextSize(getResources().getDisplayMetrics().scaledDensity * 16f);
        float widest = 0f;
        for (int label : labels) widest = Math.max(widest, paint.measureText(getString(label)));
        int wanted = Math.round(widest) + dp(48);
        // Never wider than the window (long labels, large font, split-screen).
        return Math.min(wanted, DialogSizing.maxWidthPx(
            getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().density));
    }

    private void withGameInstallation(String gameId, Runnable whenIdle) {
        libraryExecutor.execute(() -> {
            try {
                com.termux.ui.wine.api.ActiveWineInstallation active =
                    com.termux.ui.wine.api.ActiveWineInstallation.find(getFilesDir(), gameId);
                mainHandler.post(() -> {
                    if (!started || binding == null) return;
                    if (active == null) whenIdle.run();
                    else {
                        Dialog dialog = active.rootfs ? RuntimeSetupConsoleDialog.show(this, active.taskId)
                            : PrefixSetupConsoleDialog.show(this, active.taskId);
                        dialog.setOnDismissListener(ignored -> loadLibraryAsync());
                    }
                });
            } catch (IOException error) {
                mainHandler.post(() -> {
                    if (started) Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void performGameAction(GameLibraryItem item, int action) {
        withGameInstallation(item.getGame().getId(), () -> performGameActionWhenIdle(item, action));
    }

    private void performGameActionWhenIdle(GameLibraryItem item, int action) {
        Game game = item.getGame();
        switch (action) {
            case GAME_ACTION_ENGINE:
                startActivity(LocalGames.createRuntimeOptionsIntent(this, game.getId()));
                return;
            case GAME_ACTION_EDIT:
                startActivity(LocalGames.createGameDetailIntent(this, game.getId()));
                return;
            case GAME_ACTION_STORAGE:
                startActivity(LocalGames.createGameAssetsIntent(this, game.getId()));
                return;
            case GAME_ACTION_CDRIVE_BACKUP:
                ensureBackupStoragePermission(() -> cdriveBackupFlow.show(game));
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

    /** Dialog surface (glass fill, scrim): configured before the sheet is shown. */
    private void configurePresentationSurface(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) return;
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        // The window background is the one glass fill (8 dp, hairline, blur on API 31+); the dialog
        // layouts themselves are transparent. Light mode gets the warm scrim instead of a black dim.
        FrostedWindow.applyDialogSurface(window, this);
        FrostedWindow.applyScrim(dialog);
    }

    /**
     * Size and gravity of a sheet for the current window: bottom sheet in portrait, centred card in
     * landscape, never wider than {@code maxWidthDp} (0 = no extra cap) and never taller than the
     * window. Re-run when the configuration changes while the sheet is open.
     */
    private void fitPresentationWindow(Dialog dialog, ConfigurationAwareFrameLayout root,
                                       int maxWidthDp) {
        Window window = dialog.getWindow();
        if (window == null) return;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        boolean landscape = DialogSizing.isLandscape(metrics.widthPixels, metrics.heightPixels);
        WindowManager.LayoutParams attributes = window.getAttributes();
        attributes.gravity = landscape ? Gravity.CENTER : Gravity.BOTTOM;
        window.setAttributes(attributes);
        int width = landscape ? Math.min(metrics.widthPixels - dp(96), dp(680))
            : metrics.widthPixels - dp(16);
        if (maxWidthDp > 0) width = Math.min(width, dp(maxWidthDp));
        width = Math.max(Math.min(dp(240), metrics.widthPixels), width);
        root.setMaxHeightPx(DialogSizing.maxHeightPx(metrics.heightPixels, metrics.density));
        window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private Dialog createPresentationDialog(View content, int maxWidthDp) {
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ConfigurationAwareFrameLayout root = new ConfigurationAwareFrameLayout(this);
        root.addView(content, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // The Activity is not recreated on rotation, so an open sheet re-fits itself; posted so the
        // resources already describe the new window when the size is read.
        root.setListener(ignored -> root.post(() -> {
            if (dialog.isShowing()) fitPresentationWindow(dialog, root, maxWidthDp);
        }));
        dialog.setContentView(root);
        // Dialog.show() attaches the window before delivering OnShowListener. Configure the final
        // bounds first so WindowManager never animates from the theme's initial size and gravity.
        configurePresentationSurface(dialog);
        fitPresentationWindow(dialog, root, maxWidthDp);
        return dialog;
    }

    private void showInvalidProfile(Game game, String error) {
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_game_profile_invalid)
            .setMessage(getString(R.string.local_game_profile_invalid_details, error))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_game_action_remove, (dialog, which) -> confirmRemove(game))
            .show();
    }

    private void confirmRemove(Game game) {
        new FrostedDialogBuilder(this)
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
                new GameUninstaller(getFilesDir(), com.termux.ui.api.LocalGames.requireHost(this)).execute(
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
        binding.localGamesLibraryError.setText(getString(
            R.string.local_games_library_load_failed, message));
        binding.localGamesLibraryError.setVisibility(View.VISIBLE);
        // Importing does not depend on the library load: the grid keeps the Import card alone.
        populateLibraryGrid(Collections.emptyList(), libraryGeneration);
    }


    /** @return true when another installation or reset owns the single execution slot. */
    private boolean showActiveInstallationConsole() {
        try {
            for (PrefixSetupTask task : new FilePrefixSetupTaskRepository(
                new GameStoragePaths(getFilesDir()).getPrefixSetupTasksDirectory()).list()) {
                if (!task.getState().isTerminal()) {
                    PrefixSetupConsoleDialog.show(this, task.getTaskId());
                    return true;
                }
            }
        } catch (IOException error) {
            Toast.makeText(this, safeMessage(error), Toast.LENGTH_LONG).show();
            return true;
        }
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
            RuntimeBackupFlow.instance().watchRootfsTask(backup.getTaskId(),
                backup.getKind() == RootfsBackupTask.Kind.BACKUP
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
        if (TERMUX_GLIBC_ID.equals(componentId)) {
            RuntimeSetupConsoleDialog.showComponent(this, taskId,
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

    /** The file picker imports into the explicitly selected Wine collection. */
    private void startImportFlow() {
        startActivity(UiNavigation.tabIntent(LocalGames.createFileManagerIntent(this))
            .putExtra(GameFileManagerActivity.EXTRA_IMPORT_BACKEND,
                (collectionToolbar.isProot() ? GameRuntimeBackendType.ROOTFS_PROOT
                    : GameRuntimeBackendType.GLIBC_TERMUX_BOX).getStorageValue())
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        overridePendingTransition(0, 0);
    }

    /** Refreshes the readiness shown on the Native / Proot Environment rows in Settings. */
    private void renderRuntimeStatus() {
        libraryExecutor.execute(() -> {
            GameStoragePaths paths = new GameStoragePaths(getFilesDir());
            RuntimeEnvironmentStatus status = new RuntimeEnvironmentStatus(paths);
            RuntimeReadinessState glibcState = status.glibcState();
            RuntimeReadinessState rootfsState = status.rootfsState();
            ResetTask activeGlibcReset = findActiveResetTask(paths, ResetTarget.GLIBC);
            mainHandler.post(() -> {
                if (binding == null) return;
                // A running reset reads as "incomplete" even if the files are not gone yet.
                glibcStatus = activeGlibcReset != null ? RuntimeReadinessState.INCOMPLETE : glibcState;
                rootfsStatus = rootfsState;
                renderSettingsMenu();
            });
        });
    }

    private String environmentStateLabel(@Nullable RuntimeReadinessState state) {
        if (state == null) return getString(R.string.local_games_environment_state_checking);
        switch (state) {
            case READY: return getString(R.string.local_games_environment_state_ready);
            case INCOMPLETE: return getString(R.string.local_games_environment_state_incomplete);
            default: return getString(R.string.local_games_environment_state_not_installed);
        }
    }

    private interface ReadinessAction {
        void run(RuntimeReadinessState glibc, RuntimeReadinessState rootfs);
    }

    /** Reads both environments' readiness off the main thread, then runs {@code action} on it. */
    private void withReadiness(ReadinessAction action) {
        libraryExecutor.execute(() -> {
            RuntimeEnvironmentStatus status =
                new RuntimeEnvironmentStatus(new GameStoragePaths(getFilesDir()));
            RuntimeReadinessState glibc = status.glibcState();
            RuntimeReadinessState rootfs = status.rootfsState();
            mainHandler.post(() -> {
                if (binding != null) action.run(glibc, rootfs);
            });
        });
    }

    // ---- Settings > Core components > Native / Proot ----

    private String settingsPageTitle(SettingsPage page) {
        switch (page) {
            case CORE_COMPONENTS: return getString(R.string.local_games_settings_core_components);
            case NATIVE: return getString(R.string.collection_mode_native);
            case PROOT: return getString(R.string.collection_mode_proot);
            default: return getString(R.string.local_games_settings_title);
        }
    }

    private void updateSettingsBackCallback() {
        if (settingsBackCallback != null) {
            settingsBackCallback.setEnabled(selectedTab == TAB_LIBRARY || LocalGamesBackNavigation.interceptsSystemBack(
                selectedTab == TAB_LIBRARY, selectedTab == TAB_SETTINGS, settingsPage.isRoot()));
        }
    }

    private void navigateBackOrConfirmExit() {
        if (navigateBackInLibraryScreen()) {
            resetExitConfirmation();
            return;
        }
        if (exitConfirmation.onBack(android.os.SystemClock.elapsedRealtime())) {
            if (exitToast != null) exitToast.cancel();
            LocalGames.requireHost(this).exitApp();
        } else {
            if (exitToast != null) exitToast.cancel();
            exitToast = Toast.makeText(this, R.string.local_games_back_again_exit, Toast.LENGTH_SHORT);
            exitToast.show();
        }
    }

    private void resetExitConfirmation() {
        exitConfirmation.reset();
        if (exitToast != null) exitToast.cancel();
        exitToast = null;
    }

    /** Performs one navigation step; false means the caller should confirm app exit. */
    private boolean navigateBackInLibraryScreen() {
        switch (LocalGamesBackNavigation.resolve(
            selectedTab == TAB_LIBRARY, selectedTab == TAB_SETTINGS, settingsPage.isRoot())) {
            case GO_UP_IN_SETTINGS:
                goBackInSettings();
                return true;
            case SHOW_LIBRARY:
                showPage(TAB_LIBRARY);
                return true;
            default:
                return false;
        }
    }

    private void openSettingsPage(SettingsPage page) {
        resetExitConfirmation();
        settingsPage = page;
        renderSettingsMenu();
        updateChrome();
        updateSettingsBackCallback();
    }

    private void goBackInSettings() {
        SettingsPage parent = settingsPage.parent();
        if (parent != null) openSettingsPage(parent);
    }

    private void renderSettingsMenu() {
        if (binding == null) return;
        SettingsMenuBuilder menu = new SettingsMenuBuilder(this, binding.localGamesSettingsMenu);
        // Sub-pages keep their title in the content and expose Up in the toolbar.
        String pageTitle = settingsPageTitle(settingsPage);
        if (!settingsPage.isRoot()) {
            menu.heading(pageTitle);
        }
        switch (settingsPage) {
            case CORE_COMPONENTS:
                menu.row(getString(R.string.collection_mode_native),
                    environmentStateLabel(glibcStatus), view -> openSettingsPage(SettingsPage.NATIVE));
                menu.row(getString(R.string.collection_mode_proot),
                    environmentStateLabel(rootfsStatus), view -> openSettingsPage(SettingsPage.PROOT));
                break;
            case NATIVE:
                renderEnvironmentPage(menu, glibcStatus, this::buildNativeFromNetwork);
                break;
            case PROOT:
                renderEnvironmentPage(menu, rootfsStatus, this::buildProotFromNetwork);
                menu.row(getString(R.string.local_games_environment_restore_from_file),
                    getString(R.string.local_games_environment_restore_summary),
                    view -> restoreProotFromFile());
                menu.row(getString(R.string.local_games_environment_backup_to_file),
                    getString(R.string.local_games_environment_backup_summary),
                    view -> backupProotToFile());
                break;
            default:
                renderSettingsRoot(menu);
                break;
        }
    }

    private void renderSettingsRoot(SettingsMenuBuilder menu) {
        String modeTitle = getString(R.string.local_games_app_mode_title);
        if (appHost != null) {
            String[] modes = {getString(R.string.local_games_app_mode_terminal),
                getString(R.string.local_games_app_mode_games)};
            int checked = appHost.getAppExperienceMode() == AppExperienceMode.GAMES ? 1 : 0;
            menu.choice(modeTitle, modes, checked, which -> confirmAppExperienceSwitch(
                which == 1 ? AppExperienceMode.GAMES : AppExperienceMode.TERMINAL));
        } else {
            menu.row(modeTitle, null, null);
        }
        menu.row(getString(R.string.local_games_settings_core_components),
            getString(R.string.local_games_settings_core_components_summary),
            view -> openSettingsPage(SettingsPage.CORE_COMPONENTS));
        XServerSettings.render(this, menu, this::renderSettingsMenu);
        UiThemePreference[] themes = UiThemePreference.values();
        String[] themeLabels = new String[themes.length];
        for (int index = 0; index < themes.length; index++) {
            themeLabels[index] = getString(themes[index].getLabelRes());
        }
        menu.choice(getString(R.string.local_games_settings_theme), themeLabels,
            UiThemePreference.load(this).ordinal(), which -> {
                themes[which].save(this);
                renderSettingsMenu();
                UiThemePreference.applyTo(this);
            });
        OrientationPreference[] choices = OrientationPreference.values();
        String[] labels = new String[choices.length];
        for (int index = 0; index < choices.length; index++) labels[index] = orientationLabel(choices[index]);
        menu.choice(getString(R.string.local_games_settings_orientation), labels,
            OrientationPreference.load(this).ordinal(), which -> {
                choices[which].save(this);
                renderSettingsMenu();
                // Changing the requested orientation may recreate this Activity; the choice is
                // already stored, so onCreate applies it again.
                setRequestedOrientation(choices[which].getRequestedOrientation());
            });
    }

    /** Common status and network installation/rebuild actions. */
    private void renderEnvironmentPage(SettingsMenuBuilder menu, @Nullable RuntimeReadinessState state,
                                       Runnable buildFromNetwork) {
        menu.row(getString(R.string.local_games_settings_status), environmentStateLabel(state), null);
        menu.row(getString(R.string.local_games_environment_build_from_network),
            getString(state == RuntimeReadinessState.NOT_READY
                ? R.string.local_games_environment_build_summary_install
                : R.string.local_games_environment_build_summary_rebuild),
            view -> buildFromNetwork.run());
    }

    private void buildNativeFromNetwork() {
        if (showActiveInstallationConsole()) return;
        withReadiness((glibc, rootfs) -> {
            if (glibc == RuntimeReadinessState.NOT_READY) confirmGlibcInstall();
            else confirmNativeRebuild();
        });
    }

    /** An installed Native runtime is rebuilt as remove-then-install. The removal is the
     *  package-manager reset (it purges the glibc packages before sweeping the directory, so
     *  dpkg stays consistent); the install starts once that finished and the runtime is gone. */
    private void confirmNativeRebuild() {
        if (showActiveInstallationConsole()) return;
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_games_native_rebuild_confirm_title)
            .setMessage(R.string.local_games_native_rebuild_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_native_rebuild_action, (dialog, which) -> {
                String taskId = ResetTasks.enqueue(this, ResetTarget.GLIBC, null);
                RuntimeSetupConsoleDialog.show(this, taskId).setOnDismissListener(consoleDialog -> {
                    renderRuntimeStatus();
                    withReadiness((glibc, rootfs) -> {
                        if (glibc == RuntimeReadinessState.NOT_READY) installGlibcRuntime();
                    });
                });
                mainHandler.postDelayed(this::renderRuntimeStatus, 400);
            })
            .show();
    }

    private void buildProotFromNetwork() {
        if (showActiveInstallationConsole()) return;
        withReadiness((glibc, rootfs) -> {
            if (rootfs == RuntimeReadinessState.NOT_READY) confirmRootfsInstall();
            else confirmRootfsRebuild();
        });
    }

    /** The RootFS archive is several GB, so it is read in place from the file chosen in the
     *  shared-storage chooser instead of being copied anywhere first. */
    private void restoreProotFromFile() {
        if (showActiveInstallationConsole()) return;
        ensureBackupStoragePermission(() -> RuntimeBackupFlow.instance().pickArchive(
            RuntimeBackupSharedStorage.ROOTFS_ARCHIVE_EXTENSION, this::confirmBackupRestore));
    }

    private void backupProotToFile() {
        confirmBackupCreate();
    }

    private String orientationLabel(OrientationPreference choice) {
        switch (choice) {
            case VERTICAL: return getString(R.string.local_games_orientation_vertical);
            case HORIZONTAL: return getString(R.string.local_games_orientation_horizontal);
            default: return getString(R.string.local_games_orientation_auto);
        }
    }

    /** Installs the shared Native glibc runtime. */
    private void confirmGlibcInstall() {
        if (showActiveInstallationConsole()) return;
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_games_install_confirm_title)
            .setMessage(R.string.local_games_install_confirm_glibc)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_install_action, (dialog, which) ->
                installGlibcRuntime())
            .show();
    }

    private void installGlibcRuntime() {
        String taskId = ComponentTasks.enqueue(this, TERMUX_GLIBC_ID);
        RuntimeSetupConsoleDialog.showComponent(this, taskId, R.string.local_games_component_console_title)
            .setOnDismissListener(dialog -> renderRuntimeStatus());
        mainHandler.postDelayed(this::renderRuntimeStatus, 400);
    }

    /** Builds the shared RootFS base runtime used by new game containers. */
    private void confirmRootfsInstall() {
        if (showActiveInstallationConsole()) return;
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_games_install_confirm_title)
            .setMessage(R.string.local_games_install_confirm_rootfs)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_install_action, (dialog, which) ->
                installRootfsRuntime())
            .show();
    }

    /** Deletes the current shared RootFS base (if any) and rebuilds it from scratch, as one
     *  user-confirmed operation -- unlike the old reset-then-separately-rebuild flow, this can
     *  never leave the base in a torn-down state the user has to remember to come back and
     *  rebuild. Restoring a saved backup instead is its own entry in the Proot Environment menu;
     *  both routes end at the one live shared RootFS. */
    private void confirmRootfsRebuild() {
        if (showActiveInstallationConsole()) return;
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_games_rootfs_rebuild_confirm_title)
            .setMessage(R.string.local_games_rootfs_rebuild_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_rootfs_rebuild_action, (dialog, which) ->
                installRootfsRuntime())
            .show();
    }

    /** Second confirmation for restoring the external backup over the current shared RootFS --
     *  a separate step from confirmRootfsRebuild()'s own dialog since this one explains what
     *  "restore" actually does (replaces the live base + Wine prefix templates) and is also the
     *  entry point showActiveInstallationConsole() routes back to if a restore is already running. */
    private void confirmBackupRestore(File archive) {
        if (showActiveInstallationConsole()) return;
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_games_backup_restore_confirm_title)
            .setMessage(R.string.local_games_backup_restore_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_backup_restore_action, (dialog, which) ->
                startBackupRestore(archive))
            .show();
    }

    private void startBackupRestore(File archive) {
        String taskId = RootfsBackupTasks.enqueueRestore(this, archive);
        RuntimeBackupFlow.instance().watchRootfsTask(taskId,
            R.string.local_games_backup_restore_console_title);
    }

    /** Proot Environment > Backup to file -- checks that the base is built, automatically names a
     *  file in the fixed backup directory, checks free space, then archives the shared RootFS there. */
    private void confirmBackupCreate() {
        if (showActiveInstallationConsole()) return;
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        libraryExecutor.execute(() -> {
            RuntimeReadinessState rootfsState = new RuntimeEnvironmentStatus(paths).rootfsState();
            mainHandler.post(() -> {
                if (binding == null) return;
                if (rootfsState != RuntimeReadinessState.READY) {
                    Toast.makeText(this, R.string.local_games_backup_requires_base,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                if (showActiveInstallationConsole()) return;
                ensureBackupStoragePermission(() -> RuntimeBackupFlow.instance().createArchive(
                    RuntimeBackupSharedStorage.rootfsExportFileName(new Date()),
                    this::askIncludePrefixTemplates));
            });
        });
    }

    /** The Wine prefix templates can be rebuilt (a restore does it), so the user may leave them out of
     *  the backup to make it smaller. */
    private void askIncludePrefixTemplates(File archive) {
        new FrostedDialogBuilder(this)
            .setTitle(R.string.local_games_backup_templates_title)
            .setMessage(R.string.local_games_backup_templates_message)
            .setNeutralButton(android.R.string.cancel, null)
            .setNegativeButton(R.string.local_games_backup_templates_exclude,
                (dialog, which) -> startBackupCreate(archive, false))
            .setPositiveButton(R.string.local_games_backup_templates_include,
                (dialog, which) -> startBackupCreate(archive, true))
            .show();
    }

    /** Free space is checked off the main thread (the tree walk is large) before the task starts. */
    private void startBackupCreate(File archive, boolean includePrefixTemplates) {
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        RuntimeBackupFlow flow = RuntimeBackupFlow.instance();
        libraryExecutor.execute(() -> {
            try {
                File folder = archive.getParentFile();
                if (folder == null || !folder.isDirectory()) {
                    throw new IOException("runtime_backup_destination_not_shared_storage");
                }
                RuntimeBackupSharedStorage.requireRootfsBackupSpace(
                    RuntimeBackupSharedStorage.estimateRootfsBackupBytes(
                        paths.getSharedRootfsContainerDirectory(), paths.getTemplateArchiveCacheDirectory(),
                        includePrefixTemplates),
                    new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes(),
                    new StatFs(folder.getAbsolutePath()).getAvailableBytes());
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> flow.showFailure(error));
                return;
            }
            mainHandler.post(() -> {
                String taskId = RootfsBackupTasks.enqueueBackup(this, archive, includePrefixTemplates);
                flow.watchRootfsTask(taskId, R.string.local_games_backup_create_console_title);
            });
        });
    }

    private void installRootfsRuntime() {
        // Shared environment setup has no game context. enqueueBaseOnly requires a real, non-DEFAULT_ID
        // containerId even though a BASE_ONLY build never creates a container under it. Use a
        // disposable id instead of forcing a game context here.
        String taskId = RuntimeSetupTasks.enqueueBaseOnly(this, "debian-13-games-rootfs",
            "shared-rebuild-" + UUID.randomUUID());
        RuntimeSetupConsoleDialog.show(this, taskId, R.string.local_games_component_console_title)
            .setOnDismissListener(dialog -> renderRuntimeStatus());
        mainHandler.postDelayed(this::renderRuntimeStatus, 400);
    }

    /** Marks a Library card as "container build in progress" with a red/breathing background
     *  (a breathing color treatment applied to the card background
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

    private static String safeMessage(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        if (cdriveBackupFlow != null) cdriveBackupFlow.release();
        RuntimeBackupFlow.instance().detach(this);
        libraryExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
