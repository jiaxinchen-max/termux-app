package com.termux.ui.activity;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.StatFs;
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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.ui.R;
import com.termux.ui.api.ComponentTasks;
import com.termux.ui.api.RootfsBackupTasks;
import com.termux.ui.api.RuntimeSetupTasks;
import com.termux.ui.api.PrefixSetupTasks;
import com.termux.ui.api.AppExperienceMode;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.artwork.GameArtworkLoader;
import com.termux.ui.artwork.GameArtworkStore;
import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.components.catalog.ComponentCatalogItem;
import com.termux.ui.components.catalog.ComponentCatalogRepository;
import com.termux.ui.components.catalog.ComponentCatalogState;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.components.index.ComponentDescriptor;
import com.termux.ui.components.index.ComponentType;
import com.termux.ui.components.install.ComponentInstallationReader;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.FileGameRepository;
import com.termux.ui.data.FilePrefixSetupTaskRepository;
import com.termux.ui.data.FileResetTaskRepository;
import com.termux.ui.data.FileRootfsBackupTaskRepository;
import com.termux.ui.data.FileRuntimeProfileRepository;
import com.termux.ui.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.data.GameAccessState;
import com.termux.ui.data.GameLibraryItem;
import com.termux.ui.data.GameLibraryRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.platform.OrientationPreference;
import com.termux.ui.data.SettingsPage;
import com.termux.ui.databinding.ActivityLocalGamesBinding;
import com.termux.ui.databinding.DialogGameActionsBinding;
import com.termux.ui.databinding.DialogGameLaunchBinding;
import com.termux.ui.databinding.ItemLocalGamesComponentBinding;
import com.termux.ui.databinding.ItemLocalGamesComponentSectionBinding;
import com.termux.ui.databinding.ItemLocalGameBinding;
import com.termux.ui.databinding.ItemLocalGameImportBinding;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.Game;
import com.termux.ui.domain.GameRuntimeBackendType;
import com.termux.ui.domain.PrefixSetupTask;
import com.termux.ui.domain.ResetTarget;
import com.termux.ui.domain.ResetTask;
import com.termux.ui.domain.RootfsBackupTask;
import com.termux.ui.runtime.RuntimeBackupSharedStorage;
import com.termux.ui.domain.RuntimeReadinessState;
import com.termux.ui.api.ResetTasks;
import com.termux.ui.runtime.RuntimeEnvironmentStatus;
import com.termux.ui.runtime.RootfsSetupRecipe;
import com.termux.ui.domain.RuntimeSetupTask;
import com.termux.ui.domain.RuntimeProfile;
import com.termux.ui.importer.SafGameAccessProbe;
import com.termux.ui.recovery.GameUninstallPlan;
import com.termux.ui.recovery.GameUninstaller;
import com.termux.shared.android.PermissionUtils;

import java.io.File;
import java.io.IOException;
import java.util.Date;
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
        "com.termux.ui.extra.COMPONENT_GAME_ID";
    private static final String INDEX_ASSET = "termux-box-packages/index-v1.json";
    private static final String TERMUX_GLIBC_ID = "termux-glibc";
    private static final String STATE_SELECTED_TAB = "local_games.selected_tab";
    private static final String STATE_SETTINGS_PAGE = "local_games.settings_page";
    private boolean navigationUpdating;
    /** Last statusBars top inset delivered to the top tab bar, or -1 before the first dispatch. */
    private int dispatchedStatusBarInsetPx = -1;
    /** Whether {@link #dispatchedStatusBarInsetPx} was received in a landscape window. */
    private boolean dispatchedStatusBarInsetLandscape;
    /** False from a configuration change until the next insets dispatch: root insets are stale. */
    private boolean insetsDispatchedSinceConfigChange;
    private static final int TAB_LIBRARY = 0;
    private static final int TAB_COMPONENTS = 1;
    private static final int TAB_SETTINGS = 2;
    private static final int GAME_ACTION_ENGINE = 0;
    private static final int GAME_ACTION_EDIT = 1;
    private static final int GAME_ACTION_BACKUP = 2;
    private static final int GAME_ACTION_CONTROLS = 3;
    private static final int GAME_ACTION_REAUTHORIZE = 4;
    private static final int GAME_ACTION_REMOVE = 5;
    /** The launch sheet is a compact card; wider windows do not stretch it. */
    private static final int LAUNCH_SHEET_MAX_WIDTH_DP = 440;
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
    /** The items and grid width of the last render, so the grid re-flows when its width changes
     *  (rotation, split-screen resize) without reloading the data. */
    @Nullable private List<GameLibraryItem> renderedLibraryItems;
    private int renderedGridWidth;
    @Nullable private PopupWindow moreMenuPopup;
    private Runnable pendingBackupStorageAction;
    private Map<String, BuildingTask> buildingGameTasks = Collections.emptyMap();
    private static final long BUILDING_POLL_MS = 2500L;
    private final Runnable buildingPoll = () -> loadLibraryAsync(true);
    private final List<android.animation.ObjectAnimator> libraryCardBreathAnimators =
        new ArrayList<>();
    private RuntimeSetupTask latestRuntimeSetupTask;
    private boolean rootfsRuntimeInstalled;
    /** Which menu level the Settings page shows, and the environment states its rows display
     *  (null until the first background check has finished). */
    private SettingsPage settingsPage = SettingsPage.ROOT;
    @Nullable private RuntimeReadinessState glibcStatus;
    @Nullable private RuntimeReadinessState rootfsStatus;
    private OnBackPressedCallback settingsBackCallback;
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
        setRequestedOrientation(OrientationPreference.load(this).getRequestedOrientation());
        binding = ActivityLocalGamesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        configureResponsiveLibrary();
        componentGameId = getIntent().getStringExtra(EXTRA_COMPONENT_GAME_ID);

        selectedTab = savedInstanceState == null
            ? (!TextUtils.isEmpty(componentGameId)
                ? TAB_COMPONENTS : TAB_LIBRARY)
            : savedInstanceState.getInt(STATE_SELECTED_TAB, TAB_LIBRARY);
        // Applied after the initial showPage() below: selecting the library item first runs
        // showPage(TAB_LIBRARY), which resets the Settings level.
        SettingsPage restoredSettingsPage = savedInstanceState == null ? SettingsPage.ROOT
            : SettingsPage.fromOrdinal(savedInstanceState.getInt(STATE_SETTINGS_PAGE, 0));
        binding.localGamesToolbar.setNavigationOnClickListener(view -> {
            if (!navigateBackInLibraryScreen()) finish();
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
        binding.localGamesComponentTaskConsole.setOnClickListener(view ->
            showActiveInstallationConsole());
        // System BACK behaves like the toolbar arrow (LocalGamesBackNavigation): second-level
        // Settings page -> first level, any other non-Library page -> Library. On the Library tab
        // the callback is disabled and back leaves the screen as before.
        settingsBackCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                navigateBackInLibraryScreen();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, settingsBackCallback);
        // One destination handler for both chromes: the bottom bar (portrait) and the icon tabs
        // at the top (landscape).
        binding.localGamesNavigation.setOnItemSelectedListener(item ->
            onNavigationDestination(item.getItemId()));
        binding.localGamesTopTabLibrary.setOnClickListener(view ->
            onNavigationDestination(R.id.local_games_navigation_library));
        binding.localGamesTopTabFileManager.setOnClickListener(view ->
            onNavigationDestination(R.id.local_games_navigation_file_manager));
        binding.localGamesTopTabSettings.setOnClickListener(view ->
            onNavigationDestination(R.id.local_games_navigation_settings));
        WindowInsetsPadding.apply(binding.localGamesRoot, true, true, true, true);
        // The root insets are null before the window is attached and still the old orientation's
        // while onConfigurationChanged runs, so the bar height is also (re)applied from every fresh
        // dispatch: cold start, rotation and tab switches then all end at the status bar height.
        // The dispatched value is remembered together with its orientation so that a portrait
        // value is never reused for a landscape window.
        ViewCompat.setOnApplyWindowInsetsListener(binding.localGamesTopTabs, (view, insets) -> {
            dispatchedStatusBarInsetPx =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            dispatchedStatusBarInsetLandscape = isLandscape();
            insetsDispatchedSinceConfigChange = true;
            applyTopTabBarHeight(true);
            return insets;
        });
        // Last line of defence: whenever the bar is laid out again (it just became visible, the
        // window was resized by a rotation, a LayoutParams reset), re-check its height against
        // freshly resolved sources instead of trusting whatever was applied earlier. Not forced, so
        // a correct height ends the cycle.
        binding.localGamesTopTabs.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                              oldLeft, oldTop, oldRight,
                                                              oldBottom) -> {
            if (view.getVisibility() == View.VISIBLE) {
                view.post(() -> {
                    if (binding != null) applyTopTabBarHeight(false);
                });
            }
        });
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

        initializeCatalog();
        initializeLibrary();
        // The Import card is always the last grid item: it is there from the first frame, before
        // (and independent of) any library load.
        populateLibraryGrid(Collections.emptyList(), libraryGeneration);
        ComponentTasks.reconcile(this);
        RuntimeSetupTasks.reconcileAll(this);
        PrefixSetupTasks.reconcileAll(this);
        int initialPage = selectedTab;
        syncNavigationSelection(R.id.local_games_navigation_library);
        showPage(initialPage);
        if (initialPage == TAB_SETTINGS && !restoredSettingsPage.isRoot()) {
            openSettingsPage(restoredSettingsPage);
        }
        ResetTasks.reconcileAll(this);
        RootfsBackupTasks.reconcileAll(this);
        RuntimeBackupFlow.instance().attach(this, this::renderRuntimeStatus);
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
        // finishes -- re-check both environments so the Settings entries show the new state.
        renderRuntimeStatus();
    }

    @Override
    protected void onStop() {
        started = false;
        mainHandler.removeCallbacks(catalogRefresh);
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
        // File manager and Settings are reached from the navigation (bottom bar in portrait, top
        // icon tabs in landscape); Import is the last card of the game grid itself.
        binding.localGamesCount.setVisibility(landscape ? View.GONE : View.VISIBLE);
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
        if (selectedTab != TAB_SETTINGS) settingsPage = SettingsPage.ROOT;
        boolean showComponents = selectedTab == TAB_COMPONENTS;
        boolean showSettings = selectedTab == TAB_SETTINGS;
        binding.localGamesLibraryPage.setVisibility(
            !showComponents && !showSettings ? View.VISIBLE : View.GONE);
        binding.localGamesComponentsPage.setVisibility(showComponents ? View.VISIBLE : View.GONE);
        binding.localGamesSettingsPage.setVisibility(showSettings ? View.VISIBLE : View.GONE);
        updateChrome();
        updateSettingsBackCallback();
        if (showComponents && started) scheduleCatalogRefresh(0);
        else if (showSettings) renderAppExperience();
        else if (started) loadLibraryAsync();
    }

    private void updateChrome() {
        boolean landscape = getResources().getConfiguration().orientation ==
            Configuration.ORIENTATION_LANDSCAPE;
        boolean gamesMode = appHost != null &&
            appHost.getAppExperienceMode() == AppExperienceMode.GAMES;
        boolean topTabs = LocalGamesBackNavigation.topTabsVisible(
            landscape, selectedTab == TAB_LIBRARY, selectedTab == TAB_SETTINGS);
        boolean bottomBar = LocalGamesBackNavigation.bottomBarVisible(
            landscape, selectedTab == TAB_LIBRARY, selectedTab == TAB_SETTINGS);
        binding.localGamesNavigation.setVisibility(bottomBar ? View.VISIBLE : View.GONE);
        binding.localGamesNavigationDivider.setVisibility(bottomBar ? View.VISIBLE : View.GONE);
        binding.localGamesTopTabs.setVisibility(topTabs ? View.VISIBLE : View.GONE);
        // Sized when it becomes visible, unconditionally: its previous LayoutParams height may
        // come from the other orientation. The re-request makes the window deliver the insets of
        // the current orientation, and the post runs once this pass's layout has settled.
        if (TopTabBarHeight.shouldApplyNow(topTabs)) {
            applyTopTabBarHeight(true);
            ViewCompat.requestApplyInsets(binding.localGamesTopTabs);
            binding.localGamesTopTabs.post(() -> {
                if (binding != null) applyTopTabBarHeight(true);
            });
        }
        if (selectedTab != TAB_COMPONENTS) {
            int highlighted = LocalGamesBackNavigation.highlight(
                selectedTab == TAB_SETTINGS) == LocalGamesBackNavigation.Highlight.SETTINGS
                ? R.id.local_games_navigation_settings : R.id.local_games_navigation_library;
            // The top tabs are plain views and always follow; the bottom bar is already on the
            // tapped item while its own listener runs.
            syncTopTabSelection(highlighted);
            if (!navigationUpdating) syncNavigationSelection(highlighted);
        }
        binding.localGamesToolbar.setVisibility(
            selectedTab != TAB_LIBRARY || (!gamesMode && !landscape) ? View.VISIBLE : View.GONE);
        if (selectedTab == TAB_COMPONENTS) {
            binding.localGamesToolbar.setTitle(R.string.local_games_tab_components);
        } else if (selectedTab == TAB_SETTINGS) {
            binding.localGamesToolbar.setTitle(settingsPageTitle(settingsPage));
        } else {
            binding.localGamesToolbar.setTitle(R.string.local_games_title);
        }
    }

    /** Handles a tap on a navigation destination of either chrome; returns whether the bottom bar
     *  should select the item (the File manager entry never becomes selected). */
    private boolean onNavigationDestination(int itemId) {
        // Programmatic syncs (showPage) must not re-enter navigation.
        if (navigationUpdating) return true;
        if (itemId == R.id.local_games_navigation_file_manager) {
            // An entry, not a tab: returning from the file manager lands on the Library.
            if (LocalGamesBackNavigation.showsLibraryBeforeFileManager(
                    selectedTab == TAB_LIBRARY)) {
                showPage(TAB_LIBRARY);
            }
            startImportFlow();
            return false;
        }
        boolean settings = itemId == R.id.local_games_navigation_settings;
        // Tapping the tab that is already showing does nothing, like re-selecting a bottom bar item.
        if (selectedTab == (settings ? TAB_SETTINGS : TAB_LIBRARY)) return true;
        navigationUpdating = true;
        try {
            showPage(settings ? TAB_SETTINGS : TAB_LIBRARY);
        } finally {
            navigationUpdating = false;
        }
        return true;
    }

    private boolean isLandscape() {
        return getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    /** Sizes the landscape top tab bar to the system status bar: the live WindowInsets height if the
     *  window is given it for the current orientation, else the orientation's
     *  {@code status_bar_height*} resource, else 24 dp. The resource is read by name because that is
     *  the only way to reach it; an unreadable value falls back. A hidden bar is not sized; with
     *  {@code force} the height is applied even if it equals the current LayoutParams value, which
     *  may have been resolved for the other orientation. */
    @SuppressLint({"DiscouragedApi", "InternalInsetResource"})
    private void applyTopTabBarHeight(boolean force) {
        if (binding == null) return;
        boolean visible = binding.localGamesTopTabs.getVisibility() == View.VISIBLE;
        if (!TopTabBarHeight.shouldApplyNow(visible)) return;
        boolean landscape = isLandscape();
        int insetsPx = TopTabBarHeight.usableCachedInset(
            dispatchedStatusBarInsetPx, dispatchedStatusBarInsetLandscape, landscape);
        if (insetsPx < 0) {
            insetsPx = 0;
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(binding.localGamesRoot);
            if (insets != null &&
                TopTabBarHeight.rootInsetsTrusted(insetsDispatchedSinceConfigChange)) {
                insetsPx = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            }
        }
        int resourcePx = 0;
        for (String name : TopTabBarHeight.statusBarResourceNames(landscape)) {
            int resourceId = getResources().getIdentifier(name, "dimen", "android");
            resourcePx = resourceId > 0 ? getResources().getDimensionPixelSize(resourceId) : 0;
            if (resourcePx > 0) break;
        }
        int height = TopTabBarHeight.resolve(insetsPx, resourcePx,
            getResources().getDimensionPixelSize(R.dimen.local_games_top_tabs_fallback_height));
        ViewGroup.LayoutParams params = binding.localGamesTopTabs.getLayoutParams();
        if (TopTabBarHeight.needsApply(params.height, height, force)) {
            params.height = height;
            binding.localGamesTopTabs.setLayoutParams(params);
        }
    }

    private void syncTopTabSelection(int menuItemId) {
        binding.localGamesTopTabLibrary.setSelected(
            menuItemId == R.id.local_games_navigation_library);
        binding.localGamesTopTabSettings.setSelected(
            menuItemId == R.id.local_games_navigation_settings);
    }

    /** Highlights the same destination in the bottom bar and the top tabs, without re-entering
     *  navigation. */
    private void syncNavigationSelection(int menuItemId) {
        boolean wasUpdating = navigationUpdating;
        navigationUpdating = true;
        try {
            binding.localGamesNavigation.setSelectedItemId(menuItemId);
            syncTopTabSelection(menuItemId);
        } finally {
            navigationUpdating = wasUpdating;
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // The Activity is not recreated on rotation: tab, Settings level, open dialogs and the
        // scroll positions stay as they are; only the orientation-dependent chrome is re-laid out.
        dismissMoreMenu();
        if (binding == null) return;
        // Everything cached for the previous orientation (dispatched inset, root insets) is stale
        // now; the bar is re-sized from the new orientation's sources right away and again once the
        // rotated layout and the new insets have arrived (updateChrome posts that pass).
        dispatchedStatusBarInsetPx = -1;
        insetsDispatchedSinceConfigChange = false;
        configureResponsiveLibrary();
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
                    boolean unchanged = pollOnly && sameBuilding(buildingGameTasks, loadedBuilding);
                    buildingGameTasks = loadedBuilding;
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
        binding.localGamesCount.setText(getString(R.string.local_games_my_games_count,
            items.size()));
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
        renderedGridWidth = gridWidth;
        libraryColumns = LibraryGridColumns.columnsFor(
            Math.round(gridWidth / getResources().getDisplayMetrics().density), landscape);
        binding.localGamesLibraryItems.setColumnCount(libraryColumns);
        for (int index = 0; index < items.size(); index++) {
            GameLibraryItem item = items.get(index);
            ItemLocalGameBinding row = ItemLocalGameBinding.inflate(
                getLayoutInflater(), binding.localGamesLibraryItems, false);
            applyGameCardLayout(row.getRoot(), index);
            renderGameRow(row, item, generation);
            binding.localGamesLibraryItems.addView(row.getRoot());
        }
        ItemLocalGameImportBinding importCard = ItemLocalGameImportBinding.inflate(
            getLayoutInflater(), binding.localGamesLibraryItems, false);
        applyGameCardLayout(importCard.getRoot(), LibraryGridColumns.importCardIndex(items.size()),
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
        row.localGameExecutable.setText(game.getExecutable());
        row.localGameAccessStatus.setText(accessLabel(item.getAccessState()));
        row.localGameAccessStatus.setTextColor(ContextCompat.getColor(this,
            item.getAccessState() == GameAccessState.ACCESSIBLE
                ? R.color.local_games_success_text : R.color.local_games_error));
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
            performGameAction(item, GAME_ACTION_BACKUP);
        });
        content.gameActionsInput.setOnClickListener(view -> {
            dialog.dismiss();
            performGameAction(item, GAME_ACTION_CONTROLS);
        });
        boolean reauthorize = item.getAccessState() != GameAccessState.ACCESSIBLE;
        if (reauthorize) {
            content.gameActionsReauthorize.setOnClickListener(view -> {
                dialog.dismiss();
                performGameAction(item, GAME_ACTION_REAUTHORIZE);
            });
        } else {
            // GridLayout keeps a cell for GONE children, which left a hole before Remove; take
            // the cell out so Controls and Remove sit next to each other.
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
        int width = textPopupWidth(item);
        // The popup is bounded to the window and scrolls, so a short (landscape) window never
        // pushes rows off screen; its position is clamped into the window as well.
        PopupWindow popup = new PopupWindow(scroll, width,
            ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(dp(3));
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
        int wanted = Math.round(widest) + dp(48);
        // Never wider than the window (long labels, large font, split-screen).
        return Math.min(wanted, DialogSizing.maxWidthPx(
            getResources().getDisplayMetrics().widthPixels, getResources().getDisplayMetrics().density));
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

    /** Dialog surface (glass fill, scrim): applied once when the sheet is shown. */
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
        dialog.setOnShowListener(ignored -> {
            configurePresentationSurface(dialog);
            fitPresentationWindow(dialog, root, maxWidthDp);
        });
        return dialog;
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
        binding.localGamesLibraryError.setText(getString(
            R.string.local_games_library_load_failed, message));
        binding.localGamesLibraryError.setVisibility(View.VISIBLE);
        // Importing does not depend on the library load: the grid keeps the Import card alone.
        populateLibraryGrid(Collections.emptyList(), libraryGeneration);
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
            if (item.getDescriptor().isHidden()) continue;
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
        return RootfsSetupRecipe.DEFAULT_SOURCE.equals(item.getDescriptor().getId());
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
            case RootfsSetupRecipe.DEFAULT_SOURCE:
                return getString(R.string.local_games_component_summary_imagefs);
            case "turnip":
                return getString(R.string.local_games_component_summary_turnip);
            case "virgl-mesa":
                return getString(R.string.local_games_component_summary_virgl);
            case "dxvk":
                return getString(R.string.local_games_component_summary_dxvk);
            case "wined3d":
                return getString(R.string.local_games_component_summary_wined3d);
            case "glibc_box64_0.2.7":
                return getString(R.string.local_games_component_summary_box64);
            case "glibc_prefix-apps_3":
                return getString(R.string.local_games_component_summary_prefix_apps);
            case "libudev":
                return getString(R.string.local_games_component_summary_libudev);
            case "en-ru-locale":
                return getString(R.string.local_games_component_summary_locale);
            case "termux-glibc":
                return getString(R.string.local_games_component_summary_glibc);
            case "glibc_scripts_29":
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
                com.termux.ui.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX)) {
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
        return descriptor != null && TERMUX_GLIBC_ID.equals(descriptor.getId());
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

    // ---- Settings menu: first level and the Native / Proot Environment pages ----

    private String settingsPageTitle(SettingsPage page) {
        switch (page) {
            case NATIVE: return getString(R.string.local_games_settings_native_environment);
            case PROOT: return getString(R.string.local_games_settings_proot_environment);
            default: return getString(R.string.local_games_settings_title);
        }
    }

    private void updateSettingsBackCallback() {
        if (settingsBackCallback != null) {
            settingsBackCallback.setEnabled(LocalGamesBackNavigation.interceptsSystemBack(
                selectedTab == TAB_LIBRARY, selectedTab == TAB_SETTINGS, settingsPage.isRoot()));
        }
    }

    /** Performs one back step inside this screen; returns false when the Library tab is showing
     *  and nothing was navigated (the caller then leaves the screen). */
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
        // updateChrome() shows this page's title in the toolbar while the Settings tab is selected,
        // so a big heading with the same text underneath would only repeat it.
        String pageTitle = settingsPageTitle(settingsPage);
        if (!SettingsMenuBuilder.repeatsToolbarTitle(selectedTab == TAB_SETTINGS, pageTitle, pageTitle)) {
            menu.heading(pageTitle);
        }
        switch (settingsPage) {
            case NATIVE:
                renderEnvironmentPage(menu, glibcStatus,
                    getString(R.string.local_games_environment_native_restore_summary),
                    getString(R.string.local_games_environment_native_backup_summary),
                    this::buildNativeFromNetwork, this::restoreNativeFromFile, this::backupNativeToFile);
                break;
            case PROOT:
                renderEnvironmentPage(menu, rootfsStatus,
                    getString(R.string.local_games_environment_native_restore_summary),
                    getString(R.string.local_games_environment_native_backup_summary),
                    this::buildProotFromNetwork, this::restoreProotFromFile, this::backupProotToFile);
                break;
            default:
                renderSettingsRoot(menu);
                break;
        }
    }

    private void renderSettingsRoot(SettingsMenuBuilder menu) {
        String modeTitle = getString(R.string.local_games_app_mode_title);
        String terminalTitle = getString(R.string.local_games_open_terminal);
        if (appHost != null) {
            String[] modes = {getString(R.string.local_games_app_mode_terminal),
                getString(R.string.local_games_app_mode_games)};
            int checked = appHost.getAppExperienceMode() == AppExperienceMode.GAMES ? 1 : 0;
            menu.choice(modeTitle, modes, checked, which -> confirmAppExperienceSwitch(
                which == 1 ? AppExperienceMode.GAMES : AppExperienceMode.TERMINAL));
            menu.row(terminalTitle, null, view -> appHost.openTerminal());
        } else {
            menu.row(modeTitle, null, null);
            menu.row(terminalTitle, null, null);
        }
        menu.row(getString(R.string.local_games_settings_native_environment),
            environmentStateLabel(glibcStatus), view -> openSettingsPage(SettingsPage.NATIVE));
        menu.row(getString(R.string.local_games_settings_proot_environment),
            environmentStateLabel(rootfsStatus), view -> openSettingsPage(SettingsPage.PROOT));
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

    /** The second-level page every environment shares: its status and the three actions. */
    private void renderEnvironmentPage(SettingsMenuBuilder menu, @Nullable RuntimeReadinessState state,
                                       String restoreSummary, String backupSummary,
                                       Runnable buildFromNetwork, Runnable restoreFromFile,
                                       Runnable backupToFile) {
        menu.row(getString(R.string.local_games_settings_status), environmentStateLabel(state), null);
        menu.row(getString(R.string.local_games_environment_build_from_network),
            getString(state == RuntimeReadinessState.NOT_READY
                ? R.string.local_games_environment_build_summary_install
                : R.string.local_games_environment_build_summary_rebuild),
            view -> buildFromNetwork.run());
        menu.row(getString(R.string.local_games_environment_restore_from_file), restoreSummary,
            view -> restoreFromFile.run());
        menu.row(getString(R.string.local_games_environment_backup_to_file), backupSummary,
            view -> backupToFile.run());
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

    private void restoreNativeFromFile() {
        if (showActiveInstallationConsole()) return;
        ensureBackupStoragePermission(() -> RuntimeBackupFlow.instance().startRestore());
    }

    private void backupNativeToFile() {
        withReadiness((glibc, rootfs) -> {
            if (glibc != RuntimeReadinessState.READY) {
                Toast.makeText(this, R.string.local_games_environment_backup_requires_install,
                    Toast.LENGTH_LONG).show();
                return;
            }
            ensureBackupStoragePermission(() -> RuntimeBackupFlow.instance().startExport());
        });
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

    /** Triggers the same shared termux-glibc-runtime component install used by the per-game
     *  Components list, without navigating there -- reasonable since the component itself is
     *  not game-specific. */
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
        RuntimeSetupConsoleDialog.show(this, taskId)
            .setOnDismissListener(dialog -> renderRuntimeStatus());
        mainHandler.postDelayed(this::renderRuntimeStatus, 400);
    }

    /** Builds the shared RootFS base runtime (box64, Wine, fonts) that new game containers are
     *  extracted from -- the same setup triggered from the per-game Components
     *  list when no such container exists yet, without navigating there. */
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

    /** Proot Environment > Backup to file -- checks that the base is built, lets the user choose a
     *  file inside shared storage, checks free space, then archives the shared RootFS there. */
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
                ensureBackupStoragePermission(() -> RuntimeBackupFlow.instance().pickSaveFile(
                    RuntimeBackupSharedStorage.ROOTFS_ARCHIVE_EXTENSION,
                    RuntimeBackupSharedStorage.rootfsExportFileName(new Date()),
                    this::startBackupCreate));
            });
        });
    }

    /** Free space is checked off the main thread (the tree walk is large) before the task starts. */
    private void startBackupCreate(File archive) {
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
                        paths.getSharedRootfsContainerDirectory(), paths.getTemplateArchiveCacheDirectory()),
                    new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes(),
                    new StatFs(folder.getAbsolutePath()).getAvailableBytes());
            } catch (IOException | RuntimeException error) {
                mainHandler.post(() -> flow.showFailure(error));
                return;
            }
            mainHandler.post(() -> {
                String taskId = RootfsBackupTasks.enqueueBackup(this, archive);
                flow.watchRootfsTask(taskId, R.string.local_games_backup_create_console_title);
            });
        });
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
        RuntimeBackupFlow.instance().detach(this);
        catalogExecutor.shutdownNow();
        libraryExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
