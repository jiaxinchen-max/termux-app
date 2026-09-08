package com.termux.localgames.activity;

import android.app.Dialog;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import com.termux.localgames.api.RuntimeProvisionTasks;
import com.termux.localgames.api.PrefixProvisionTasks;
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
import com.termux.localgames.data.FileRuntimeProvisionTaskRepository;
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
import com.termux.localgames.domain.RuntimeProvisionTask;
import com.termux.localgames.importer.SafGameAccessProbe;
import com.termux.localgames.recovery.GameUninstallPlan;
import com.termux.localgames.recovery.GameUninstaller;
import com.termux.localgames.runtime.RootfsRuntimeInstallationReader;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root Activity owned by the standalone games feature module. */
public final class LocalGamesActivity extends AppCompatActivity {

    public static final String EXTRA_OPEN_COMPONENTS =
        "com.termux.localgames.extra.OPEN_COMPONENTS";
    private static final String INDEX_ASSET = "termux-box-packages/index-v1.json";
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
    private FileRuntimeProvisionTaskRepository runtimeProvisionTaskRepository;
    private GameLibraryRepository libraryRepository;
    private GameArtworkLoader artworkLoader;
    private LocalGamesHost appHost;
    private String catalogInitializationError;
    private boolean started;
    private int selectedTab;
    private int libraryGeneration;
    private int libraryColumns = 2;
    private RuntimeProvisionTask latestRuntimeProvisionTask;
    private boolean rootfsRuntimeInstalled;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityLocalGamesBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        configureResponsiveLibrary();

        selectedTab = savedInstanceState == null
            ? (getIntent().getBooleanExtra(EXTRA_OPEN_COMPONENTS, false)
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
        binding.localGamesAddButton.setOnClickListener(view ->
            startActivity(LocalGames.createFileManagerIntent(this)));
        binding.localGamesFileManagerButton.setOnClickListener(view ->
            startActivity(LocalGames.createFileManagerIntent(this)));
        binding.localGamesComponentsButton.setOnClickListener(view -> showPage(TAB_COMPONENTS));
        binding.localGamesComponentTaskConsole.setOnClickListener(view ->
            showActiveInstallationConsole());
        binding.localGamesSettingsButton.setOnClickListener(view -> showPage(TAB_SETTINGS));
        binding.localGamesToolsButton.setOnClickListener(view -> showTools());
        configureOrientationButton();
        binding.localGamesNavigation.setOnItemSelectedListener(item -> {
            if (item.getItemId() == R.id.local_games_navigation_import) {
                startActivity(LocalGames.createFileManagerIntent(this));
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
        RuntimeProvisionTasks.reconcileAll(this);
        PrefixProvisionTasks.reconcileAll(this);
        int initialPage = selectedTab;
        binding.localGamesNavigation.setSelectedItemId(R.id.local_games_navigation_library);
        showPage(initialPage);
        renderRuntimeStatus();
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
    protected void onStop() {
        started = false;
        mainHandler.removeCallbacks(catalogRefresh);
        super.onStop();
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
            runtimeProvisionTaskRepository = new FileRuntimeProvisionTaskRepository(
                new GameStoragePaths(getFilesDir()).getRuntimeProvisionTasksDirectory());
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
        binding.localGamesOrientationButton.setText(landscape
            ? R.string.local_games_switch_portrait : R.string.local_games_switch_landscape);
        binding.localGamesOrientationButton.setContentDescription(getString(landscape
            ? R.string.local_games_switch_portrait_description
            : R.string.local_games_switch_landscape_description));
        binding.localGamesOrientationButton.setOnClickListener(view -> setRequestedOrientation(
            landscape ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
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
            getString(R.string.local_games_shortcut_components),
            getString(R.string.local_games_shortcut_settings),
            getString(R.string.local_games_open_terminal)
        };
        new MaterialAlertDialogBuilder(this)
            .setTitle(R.string.local_games_tools_title)
            .setItems(actions, (dialog, which) -> {
                if (which == 0) showPage(TAB_COMPONENTS);
                else if (which == 1) showPage(TAB_SETTINGS);
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
            String error = null;
            try {
                items = libraryRepository.load();
            } catch (IOException loadError) {
                error = safeMessage(loadError);
            }
            List<GameLibraryItem> loaded = items;
            String failure = error;
            mainHandler.post(() -> {
                libraryLoadInFlight.set(false);
                if (!started || binding == null || selectedTab != TAB_LIBRARY ||
                    generation != libraryGeneration) return;
                if (failure == null) renderLibrary(loaded, generation);
                else renderLibraryError(failure);
            });
        });
    }

    private void renderLibrary(List<GameLibraryItem> items, int generation) {
        binding.localGamesLibraryLoading.hide();
        binding.localGamesLibraryError.setVisibility(View.GONE);
        binding.localGamesLibraryItems.removeAllViews();
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
        row.getRoot().setOnClickListener(view -> showLaunchPresentation(item));
        row.getRoot().setFocusable(true);
        row.localGameDetails.setOnClickListener(view -> startActivity(
            LocalGames.createRuntimeProfileIntent(this, game.getId())));
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
                startActivity(LocalGames.createRuntimeProfileIntent(this, game.getId()));
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
            RuntimeProvisionTask provisionTask = null;
            boolean runtimeInstalled = false;
            try {
                items = catalogRepository.load();
            } catch (IOException loadError) {
                error = safeMessage(loadError);
            }
            try {
                provisionTask = latestRuntimeProvisionTask();
            } catch (IOException ignored) {
                // A damaged task record must not hide the component catalog or reinstall action.
            }
            try {
                runtimeInstalled = new RootfsRuntimeInstallationReader(
                    new GameStoragePaths(getFilesDir()))
                    .readActive("debian-13-games-rootfs").isPresent();
            } catch (IOException | RuntimeException ignored) {
                // Missing or stale activation metadata is rendered as not installed.
            }
            List<ComponentCatalogItem> loadedItems = items;
            String loadedError = error;
            RuntimeProvisionTask loadedProvisionTask = provisionTask;
            boolean loadedRuntimeInstalled = runtimeInstalled;
            mainHandler.post(() -> {
                loadInFlight.set(false);
                if (!started || binding == null || selectedTab != TAB_COMPONENTS) return;
                if (loadedError == null) {
                    latestRuntimeProvisionTask = loadedProvisionTask;
                    rootfsRuntimeInstalled = loadedRuntimeInstalled;
                    renderCatalog(loadedItems);
                }
                else renderCatalogError(loadedError);
                scheduleCatalogRefresh(REFRESH_INTERVAL_MILLIS);
            });
        });
    }

    @Nullable
    private RuntimeProvisionTask latestRuntimeProvisionTask() throws IOException {
        if (runtimeProvisionTaskRepository == null) return null;
        RuntimeProvisionTask latest = null;
        for (RuntimeProvisionTask task : runtimeProvisionTaskRepository.list()) {
            if (!"debian-13-games-rootfs".equals(task.getPackageName())) continue;
            if (latest == null || task.getCreatedAt() > latest.getCreatedAt()) latest = task;
        }
        return latest;
    }

    private void renderCatalog(List<ComponentCatalogItem> items) {
        binding.localGamesComponentsLoading.hide();
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
                    section.localGamesComponentSectionTitle.setText(componentTypeLabel(type));
                    binding.localGamesComponentSections.addView(section.getRoot());
                }
                ItemLocalGamesComponentBinding row = ItemLocalGamesComponentBinding.inflate(
                    getLayoutInflater(), section.localGamesComponentSectionItems, false);
                section.localGamesComponentSectionItems.addView(row.getRoot());
                componentRows.put(item.getDescriptor().getId(), row);
            }
        }
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
        RuntimeProvisionTask provisionTask = rootfsSource ? latestRuntimeProvisionTask : null;
        ComponentTask task = item.getTask().orElse(null);
        configurePrimaryAction(row, item, task, activationRequired, provisionTask);
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
            case "glibc-prefix":
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
                                        @Nullable RuntimeProvisionTask provisionTask) {
        int title;
        View.OnClickListener action;
        if (task != null && task.getState().shouldRecoverAutomatically()) {
            row.localGamesComponentPrimaryAction.setText(
                R.string.local_games_component_view_process);
            row.localGamesComponentPrimaryAction.setOnClickListener(view ->
                ComponentTaskConsoleDialog.show(this, task.getTaskId()));
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
            if (provisionTask != null && !provisionTask.getState().isTerminal()) {
                row.localGamesComponentPrimaryAction.setText(
                    R.string.local_games_runtime_provision_view_log_action);
                row.localGamesComponentPrimaryAction.setOnClickListener(view ->
                    RuntimeProvisionConsoleDialog.show(this, provisionTask.getTaskId()));
                row.localGamesComponentPrimaryAction.setEnabled(true);
                row.localGamesComponentPrimaryAction.setVisibility(View.VISIBLE);
                return;
            }
            if (rootfsRuntimeInstalled) {
                row.localGamesComponentPrimaryAction.setText(
                    R.string.local_games_runtime_provision_reinstall_action);
                row.localGamesComponentPrimaryAction.setOnClickListener(view -> {
                    if (showActiveInstallationConsole()) return;
                    disableActions(row);
                    String taskId = RuntimeProvisionTasks.enqueue(this, "debian-13-games-rootfs");
                    RuntimeProvisionConsoleDialog.show(this, taskId);
                    scheduleCatalogRefresh(150);
                });
                row.localGamesComponentPrimaryAction.setEnabled(true);
                row.localGamesComponentPrimaryAction.setVisibility(View.VISIBLE);
                return;
            }
            row.localGamesComponentPrimaryAction.setText(
                R.string.local_games_runtime_provision_action);
            row.localGamesComponentPrimaryAction.setOnClickListener(view -> {
                if (showActiveInstallationConsole()) return;
                disableActions(row);
                String taskId = RuntimeProvisionTasks.enqueue(this, "debian-13-games-rootfs");
                RuntimeProvisionConsoleDialog.show(this, taskId);
                Toast.makeText(this, getString(R.string.local_games_runtime_provision_started,
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
                title = R.string.local_games_component_download;
                action = view -> {
                    if (showActiveInstallationConsole()) return;
                    disableActions(row);
                    String taskId = ComponentTasks.enqueue(this, item.getDescriptor().getId());
                    ComponentTaskConsoleDialog.show(this, taskId);
                    scheduleCatalogRefresh(150);
                };
                break;
            case INSTALLED:
                title = R.string.local_games_component_reinstall;
                action = view -> {
                    if (showActiveInstallationConsole()) return;
                    disableActions(row);
                    String taskId = ComponentTasks.enqueue(this, item.getDescriptor().getId());
                    ComponentTaskConsoleDialog.show(this, taskId);
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
                    ComponentTaskConsoleDialog.show(this, task.getTaskId());
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
                    ComponentTaskConsoleDialog.show(this, task.getTaskId());
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
        boolean active = findActiveComponentTask() != null || findActiveProvisionTask() != null;
        binding.localGamesComponentTaskConsole.setVisibility(active ? View.VISIBLE : View.GONE);
    }

    /** @return true when another installation owns the single execution slot. */
    private boolean showActiveInstallationConsole() {
        ComponentTask component = findActiveComponentTask();
        if (component != null) {
            ComponentTaskConsoleDialog.show(this, component.getTaskId());
            return true;
        }
        RuntimeProvisionTask provision = findActiveProvisionTask();
        if (provision != null) {
            RuntimeProvisionConsoleDialog.show(this, provision.getTaskId());
            return true;
        }
        return false;
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
    private RuntimeProvisionTask findActiveProvisionTask() {
        try {
            if (runtimeProvisionTaskRepository == null) return null;
            for (RuntimeProvisionTask task : runtimeProvisionTaskRepository.list()) {
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

    private static void disableActions(ItemLocalGamesComponentBinding row) {
        row.localGamesComponentPrimaryAction.setEnabled(false);
    }

    private static void hidePrimaryAction(ItemLocalGamesComponentBinding row) {
        row.localGamesComponentPrimaryAction.setVisibility(View.GONE);
        row.localGamesComponentPrimaryAction.setOnClickListener(null);
    }

    private void renderCatalogError(String message) {
        binding.localGamesComponentsLoading.hide();
        binding.localGamesComponentsError.setText(
            getString(R.string.local_games_components_load_failed, message));
        binding.localGamesComponentsError.setVisibility(View.VISIBLE);
    }

    private void renderRuntimeStatus() {
        try {
            LocalGamesHost host = LocalGames.requireHost(this);
            boolean available = host.isRuntimeAvailable();
            binding.localGamesRuntimeStatus.setText(available
                ? R.string.local_games_runtime_ready : R.string.local_games_runtime_unavailable);
            binding.localGamesRuntimeStatus.setActivated(available);
        } catch (IllegalStateException error) {
            binding.localGamesRuntimeStatus.setText(R.string.local_games_runtime_not_integrated);
            binding.localGamesRuntimeStatus.setActivated(false);
        }
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
        binding = null;
        super.onDestroy();
    }
}
