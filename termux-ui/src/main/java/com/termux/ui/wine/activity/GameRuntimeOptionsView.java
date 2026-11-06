package com.termux.ui.wine.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.termux.ui.R;
import com.termux.ui.activity.FrostedDialogBuilder;
import com.termux.ui.activity.FrostedMenuRow;
import com.termux.ui.activity.GamesHelpDialog;
import com.termux.ui.activity.RuntimeComponentVersionsActivity;
import com.termux.ui.api.RuntimeComponentVariant;
import com.termux.ui.api.LocalGames;
import com.termux.ui.components.index.ComponentIndex;
import com.termux.ui.components.index.ComponentIndexParser;
import com.termux.ui.data.FileCustomRuntimeComponentRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.ui.wine.api.RuntimeWarmup;
import com.termux.ui.wine.data.FileGameRepository;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.data.RuntimeProfileRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.GameContainer;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfileDiff;
import com.termux.ui.wine.domain.RuntimeTranslator;
import com.termux.ui.wine.importer.LaunchArguments;
import com.termux.ui.wine.runtime.GameContainerProfileResolver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * A game-bound runtime parameter editor. It intentionally owns only launch selections for this
 * game; package installation and shared GLIBC runtime management remain in the engine screen.
 */
public final class GameRuntimeOptionsView extends LinearLayout {

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesRuntimeOptionsIo"));
    private RuntimeProfileRepository profileRepository;
    private FileGameRepository gameRepository;
    private FileCustomRuntimeComponentRepository customComponents;
    @Nullable private ComponentIndex componentIndex;
    private GameStoragePaths paths;
    private List<CustomRuntimeComponent> customEntries = Collections.emptyList();
    private List<RuntimeComponentVariant> installedVariants = Collections.emptyList();
    private LinearLayout content;
    private TextView title;
    private TextView backendSubtitle;
    private LinearLayout header;
    private ProgressBar progress;
    private LinearLayout progressHost;
    private ScrollView optionsScroll;
    private ExtendedFloatingActionButton saveFab;
    private FrameLayout.LayoutParams saveFabParams;
    private int saveFabInsetEnd;
    private int saveFabInsetBottom;
    private float lastDensity;
    private final String gameId;
    private final Listener listener;
    private Game game;
    private Game gameBaseline;
    private RuntimeProfile baseline;
    private RuntimeProfile profile;
    private Page page = Page.ROOT;
    private boolean destroyed;
    private boolean saving;
    private Intent restoredComponentSelection;

    private enum Page {
        ROOT, GENERAL, BASE, WINE, LIBRARIES, ENVIRONMENT, FOLDERS, ADVANCED
    }

    public interface Listener {
        void onRuntimeOptionsClosed();
        /** Called instead of {@link #onRuntimeOptionsClosed()} after a successful Save --
         *  hosts should navigate to the Library tab and, if {@code warmupTaskId} is non-null,
         *  surface that task's live setup console there. */
        void onRuntimeOptionsSaved(@Nullable String warmupTaskId, boolean rootfs);
        /** Both hosts launch the shared version page and return its exact selected id. */
        void onSelectRuntimeComponent(Intent intent, Consumer<String> onSelected);
    }

    public GameRuntimeOptionsView(Context context, String gameId, Listener listener) {
        super(context);
        this.gameId = gameId;
        this.listener = listener;
        this.paths = new GameStoragePaths(getContext().getFilesDir());
        gameRepository = new FileGameRepository(paths.getLibraryDirectory());
        profileRepository = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        customComponents = new FileCustomRuntimeComponentRepository(
            paths.getCustomRuntimeComponentsDirectory());
        addView(createContent(), new LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (gameId == null || gameId.trim().isEmpty()) {
            close();
            return;
        }
        load();
    }

    private View createContent() {
        FrameLayout root = new FrameLayout(getContext());
        root.setBackgroundColor(color(R.color.local_games_background));

        LinearLayout innerContent = new LinearLayout(getContext());
        innerContent.setOrientation(LinearLayout.VERTICAL);

        header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        // Same height and title size as every games toolbar; arrow and title share one centre line.
        header.setPadding(dp(4), 0, dp(4), 0);
        header.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.local_games_toolbar_height));
        lastDensity = getResources().getDisplayMetrics().density;
        int touchMin = getResources().getDimensionPixelSize(R.dimen.local_game_runtime_touch_min);
        ImageView back = new ImageView(getContext());
        back.setImageResource(R.drawable.local_games_ic_back);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(color(R.color.local_games_on_surface)));
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription(getContext().getString(
            androidx.appcompat.R.string.abc_action_bar_up_description));
        header.addView(back, new LinearLayout.LayoutParams(touchMin, touchMin));
        back.setOnClickListener(view -> navigateBack());
        title = text(s(R.string.local_game_runtime_title_default), 20, R.color.local_games_on_surface);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setIncludeFontPadding(false);
        title.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titleBlock = new LinearLayout(getContext());
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.addView(title);
        backendSubtitle = text("", 12, R.color.local_games_on_surface_secondary);
        backendSubtitle.setVisibility(View.GONE);
        titleBlock.addView(backendSubtitle);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMarginStart(dp(8));
        header.addView(titleBlock, titleParams);
        // The same "?" help action every games toolbar has (icon, description, GamesHelpDialog).
        ImageView help = new ImageView(getContext());
        help.setImageResource(R.drawable.local_games_ic_help);
        help.setImageTintList(android.content.res.ColorStateList.valueOf(color(R.color.local_games_on_surface)));
        help.setScaleType(ImageView.ScaleType.CENTER);
        help.setContentDescription(getContext().getString(R.string.local_games_help));
        help.setOnClickListener(view -> GamesHelpDialog.show(getContext(),
            R.string.local_games_runtime_options_help_title,
            R.string.local_games_runtime_options_help));
        header.addView(help, new LinearLayout.LayoutParams(touchMin, touchMin));
        innerContent.addView(header);

        progress = new ProgressBar(getContext());
        progressHost = new LinearLayout(getContext());
        progressHost.setGravity(Gravity.CENTER);
        progressHost.addView(progress);
        innerContent.addView(progressHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        optionsScroll = new ScrollView(getContext());
        optionsScroll.setFillViewport(true);
        content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        // Same horizontal inset as the Settings page menu (activity_local_games.xml).
        int pageInset = getResources().getDimensionPixelSize(R.dimen.local_games_page_horizontal_padding);
        // The bottom padding keeps the last row scrollable above the extended Save FAB, in
        // embedded and standalone use alike.
        content.setPadding(pageInset, dp(8), pageInset,
            getResources().getDimensionPixelSize(R.dimen.local_game_runtime_save_bar_clearance));
        optionsScroll.setClipToPadding(false);
        optionsScroll.addView(content);
        optionsScroll.setVisibility(View.GONE);
        innerContent.addView(optionsScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(innerContent, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Always-visible shortcut so a change made several sub-pages deep (e.g. Common's Wine
        // package) can be saved without first navigating back to Page.ROOT to reach the
        // Discard/Cancel/Save dialog -- see navigateBack(). Shares saveAndFinish() verbatim, so
        // its save-then-leave behavior matches that dialog's "Save" button exactly.
        saveFab = new ExtendedFloatingActionButton(getContext());
        saveFab.setText(R.string.local_game_runtime_save);
        saveFab.setMinimumHeight(touchMin);
        saveFab.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            color(R.color.local_games_primary)));
        saveFab.setTextColor(color(R.color.local_games_on_primary));
        // Matte like the rest of the page: no elevation shadow and no state-list animator (which
        // would raise the FAB's elevation/translationZ on press and hover).
        saveFab.setStateListAnimator(null);
        saveFab.setElevation(0f);
        saveFab.setTranslationZ(0f);
        saveFab.setVisibility(View.GONE);
        saveFab.setOnClickListener(view -> saveAndFinish());
        saveFabParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        saveFabParams.gravity = Gravity.BOTTOM | Gravity.END;
        applySaveFabMargins();
        root.addView(saveFab, saveFabParams);
        // Keep the FAB clear of the gesture bar / navigation bar when the window is laid out
        // behind them; the system delivers no insets (and nothing changes) when it already fits.
        ViewCompat.setOnApplyWindowInsetsListener(saveFab, (target, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());
            saveFabInsetEnd = bars.right;
            saveFabInsetBottom = bars.bottom;
            applySaveFabMargins();
            return insets;
        });
        ViewCompat.requestApplyInsets(saveFab);

        return root;
    }

    private void load() {
        ioExecutor.execute(() -> {
            try {
                try (InputStream input = getContext().getAssets().open("termux-app-packages/index-v1.json")) {
                    componentIndex = new ComponentIndexParser().parse(input);
                }
                Optional<Game> foundGame = gameRepository.find(gameId);
                if (!foundGame.isPresent()) throw new IOException("game_not_found");
                RuntimeProfile loaded = profileRepository.find(gameId).orElseThrow(() -> new IOException("runtime_profile_missing"));
                List<CustomRuntimeComponent> entries = customComponents.list();
                List<RuntimeComponentVariant> variants = new ArrayList<>();
                for (CustomRuntimeComponent.Kind kind : CustomRuntimeComponent.Kind.values()) {
                    variants.addAll(LocalGames.requireHost(getContext()).listInstalledRuntimeComponentVariants(kind, loaded.getRuntimeBackendType()));
                }
                post(() -> {
                    if (destroyed) return;
                    game = foundGame.get();
                    gameBaseline = game;
                    baseline = loaded;
                    profile = loaded;
                    customEntries = entries;
                    installedVariants = variants;
                    progressHost.setVisibility(View.GONE);
                    optionsScroll.setVisibility(View.VISIBLE);
                    saveFab.setVisibility(View.VISIBLE);
                    render();
                    if (restoredComponentSelection != null) {
                        Intent pending = restoredComponentSelection;
                        restoredComponentSelection = null;
                        restoreComponentSelection(pending);
                    }
                });
            } catch (IOException | RuntimeException error) {
                post(() -> {
                    if (!destroyed) {
                        Toast.makeText(getContext(), R.string.local_game_runtime_load_failed, Toast.LENGTH_LONG).show();
                        close();
                    }
                });
            }
        });
    }

    private void render() {
        content.removeAllViews();
        backendSubtitle.setText(getContext().getString(R.string.local_game_runtime_backend_subtitle,
            runtimeBackendLabel(profile.getRuntimeBackendType())));
        backendSubtitle.setVisibility(View.VISIBLE);
        switch (page) {
            case GENERAL: renderGeneral(); break;
            case BASE: renderBase(); break;
            case WINE: renderWine(); break;
            case LIBRARIES: renderLibraries(); break;
            case FOLDERS: renderFolders(); break;
            case ADVANCED: renderAdvanced(); break;
            case ENVIRONMENT: renderEnvironment(); break;
            default: renderRoot();
        }
        optionsScroll.post(() -> optionsScroll.scrollTo(0, 0));
    }

    private void renderRoot() {
        pageTitle(getContext().getString(R.string.local_game_runtime_title_root, game.getName()));
        addMenu(s(R.string.local_game_runtime_menu_general), workingDirectoryDisplay() + " · " + game.getExecutable(), Page.GENERAL);
        addRow(s(R.string.local_game_runtime_backend_components),
            s(R.string.local_game_runtime_backend_components_summary), true, view ->
            getContext().startActivity(LocalGames.createComponentsIntent(getContext(), game.getId())));
        addMenu(s(R.string.local_game_runtime_menu_common), profile.getResolution() + " · "
            + componentLabel(profile.getGraphicsDriver()) + " · " + componentLabel(profile.getDxWrapper()), Page.BASE);
        addMenu(s(R.string.local_game_runtime_menu_wine), s(R.string.local_game_runtime_menu_wine_summary), Page.WINE);
        addMenu(s(R.string.local_game_runtime_menu_libraries),
            s(R.string.local_game_runtime_menu_libraries_summary), Page.LIBRARIES);
        addMenu(s(R.string.local_game_runtime_menu_environment), profile.getEnvironment().isEmpty()
            ? s(R.string.local_game_runtime_env_none)
            : getResources().getQuantityString(R.plurals.local_game_runtime_env_count,
                profile.getEnvironment().size(), profile.getEnvironment().size()), Page.ENVIRONMENT);
        addMenu(s(R.string.local_game_runtime_menu_folders), s(R.string.local_game_runtime_menu_folders_summary), Page.FOLDERS);
        addMenu(s(R.string.local_game_runtime_menu_advanced), s(R.string.local_game_runtime_menu_advanced_summary), Page.ADVANCED);
    }

    private void renderGeneral() {
        pageTitle(s(R.string.local_game_runtime_menu_general));
        addEdit(s(R.string.local_game_runtime_general_game_name), game.getName(), false, value -> {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) game = game.withLaunchDetails(trimmed,
                game.getWorkingDirectory(), game.getArguments());
        });
        addEdit(s(R.string.local_game_runtime_general_working_dir), workingDirectoryDisplay(), false, value -> {
            try {
                String relative = com.termux.ui.wine.importer.GameWorkingDirectory.relative(resolvedGameRoot(), value);
                game = game.withLaunchDetails(game.getName(), relative, game.getArguments());
            } catch (IOException error) {
                Toast.makeText(getContext(), R.string.local_game_import_invalid_working_directory,
                    Toast.LENGTH_LONG).show();
            }
        });
        addEdit(s(R.string.local_game_runtime_general_launch_args), LaunchArguments.format(game.getArguments()), false, value -> {
            try {
                game = game.withLaunchDetails(game.getName(), game.getWorkingDirectory(),
                    LaunchArguments.parse(value));
            } catch (IllegalArgumentException error) {
                Toast.makeText(getContext(), R.string.local_game_runtime_general_launch_args_invalid,
                    Toast.LENGTH_LONG).show();
            }
        });
        addReadOnly(s(R.string.local_game_runtime_general_executable), game.getExecutable());
    }

    private File resolvedGameRoot() throws IOException {
        com.termux.ui.wine.api.ResolvedGameDirectory resolved =
            LocalGames.requireHost(getContext()).resolveGameDirectory(game.getRootUri());
        if (!resolved.isResolved()) throw new IOException("game_directory_unavailable");
        return new File(resolved.getPath());
    }

    private String workingDirectoryDisplay() {
        try {
            return com.termux.ui.wine.importer.GameWorkingDirectory.display(resolvedGameRoot(), game.getWorkingDirectory());
        } catch (IOException error) {
            return s(R.string.local_game_import_working_directory_missing);
        }
    }

    private String runtimeBackendLabel(GameRuntimeBackendType type) {
        return getContext().getString(type == GameRuntimeBackendType.ROOTFS_PROOT
            ? R.string.local_game_runtime_profile_backend_rootfs
            : R.string.local_game_runtime_profile_backend_glibc);
    }

    private void renderBase() {
        pageTitle(s(R.string.local_game_runtime_menu_common));
        addChoice(s(R.string.local_game_runtime_common_resolution), withCurrent(profile.getResolution(),
                "960x540", "1280x720", "1600x900", "1920x1080"), value ->
            replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
                profile.getAudioDriver(), value, profile.getBox64Preset(), profile.getEnvironment(),
                profile.getInputProfileId(), profile.getLaunchExecutionMode()));
        boolean rootfs = profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT;
        addRow(s(R.string.local_game_runtime_common_graphics_driver), componentLabel(profile.getGraphicsDriver()), true,
            view -> showGraphicsFamily(rootfs));
        addRow(s(R.string.local_game_runtime_common_dx), componentLabel(profile.getDxWrapper()), true,
            view -> showDxFamily(rootfs));
        addComponentVersionRow(s(R.string.local_game_runtime_common_wine_package), CustomRuntimeComponent.Kind.WINE,
            profile.getWinePackage(), rootfs ? lockedRootfsTranslator() : null,
            value -> replace(value, profile.getGraphicsDriver(), profile.getDxWrapper(),
                profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(),
                profile.getInputProfileId(), profile.getLaunchExecutionMode()));
        addChoice(s(R.string.local_game_runtime_common_audio_driver), withCurrent(profile.getAudioDriver(), "alsa", "pulseaudio"),
            value -> replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
                value, profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(),
                profile.getInputProfileId(), profile.getLaunchExecutionMode()));
        addChoice(s(R.string.local_game_runtime_common_show_fps),
            labels(s(R.string.local_game_runtime_common_off), s(R.string.local_game_runtime_common_on)), "1".equals(option("GAMES_SHOW_FPS")) ? 1 : 0,
            which -> setOption("GAMES_SHOW_FPS", which == 1 ? "1" : "0"));
    }

    private void renderWine() {
        pageTitle(s(R.string.local_game_runtime_menu_wine));
        addChoice(s(R.string.local_game_runtime_wine_language), labels(
            s(R.string.local_game_runtime_wine_language_default),
            s(R.string.local_game_runtime_wine_language_english),
            s(R.string.local_game_runtime_wine_language_chinese)),
            localeChoiceIndex(option("GAMES_LOCALE")), which -> setOption("GAMES_LOCALE",
                new String[] { "", "en_US.UTF-8", "zh_CN.UTF-8" }[which]));
        addChoice(s(R.string.local_game_runtime_wine_theme), labels(
            s(R.string.local_game_runtime_wine_theme_light),
            s(R.string.local_game_runtime_wine_theme_dark)),
            "dark".equalsIgnoreCase(option("GAMES_WINE_THEME")) ? 1 : 0, which -> setOption("GAMES_WINE_THEME", which == 0 ? "light" : "dark"));
        addEdit(s(R.string.local_game_runtime_wine_background), option("GAMES_WINE_BACKGROUND"), false,
            value -> setOption("GAMES_WINE_BACKGROUND", value));
        addChoice(s(R.string.local_game_runtime_wine_font), labels("Tahoma", "Arial", "Segoe UI"),
            choiceIndex(option("GAMES_WINE_FONT"), "Tahoma", "Arial", "Segoe UI"),
            which -> setOption("GAMES_WINE_FONT", labels("Tahoma", "Arial", "Segoe UI")[which]));
        addChoice("DPI", labels("96", "120", "144", "192"),
            choiceIndex(option("GAMES_WINE_DPI"), "96", "120", "144", "192"),
            which -> setOption("GAMES_WINE_DPI", labels("96", "120", "144", "192")[which]));
        addChoice(s(R.string.local_game_runtime_wine_mouse_overlay),
            labels(s(R.string.local_game_runtime_wine_mouse_disable),
                s(R.string.local_game_runtime_wine_mouse_force)),
            "force".equals(option("GAMES_MOUSE_WARP")) ? 1 : 0,
            which -> setOption("GAMES_MOUSE_WARP", which == 1 ? "force" : "disable"));
    }

    private void renderLibraries() {
        pageTitle(s(R.string.local_game_runtime_menu_libraries));
        addDllChoice("Direct3D", "GAMES_DLL_D3D");
        addDllChoice("DirectSound", "GAMES_DLL_DSOUND");
        addDllChoice("DirectMusic", "GAMES_DLL_DMUSIC");
        addDllChoice("DirectShow", "GAMES_DLL_DSHOW");
        addDllChoice("DirectPlay", "GAMES_DLL_DPLAY");
        addDllChoice("DirectInput", "GAMES_DLL_DINPUT");
        addDllChoice("XInput", "GAMES_DLL_XINPUT");
    }

    private void renderFolders() {
        pageTitle(s(R.string.local_game_runtime_menu_folders));
        addEdit("D:", option("GAMES_DRIVE_D"), false, value -> setOption("GAMES_DRIVE_D", value));
        addEdit("E:", option("GAMES_DRIVE_E"), false, value -> setOption("GAMES_DRIVE_E", value));
    }

    private void renderEnvironment() {
        pageTitle(s(R.string.local_game_runtime_page_environment));
        for (Map.Entry<String, String> entry : profile.getEnvironment().entrySet()) {
            addEnvironmentEntry(entry.getKey(), entry.getValue());
        }
        addRow(s(R.string.local_game_runtime_env_add_row), "", true, view -> showAddEnvironmentVariable());
    }

    private void addEnvironmentEntry(String key, String value) {
        addRow(key, value, true, view -> {
            EditText input = new EditText(getContext());
            input.setText(value);
            input.setSingleLine(false);
            input.setTextColor(color(R.color.local_games_on_surface));
            new FrostedDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
                .setTitle(key)
                .setView(scrollable(input))
                .setNegativeButton(R.string.local_game_runtime_env_delete, (dialog, which) -> {
                    setOption(key, "");
                    render();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_game_runtime_env_apply, (dialog, which) -> {
                    setOption(key, input.getText().toString());
                    render();
                })
                .show();
        });
    }

    private void showAddEnvironmentVariable() {
        LinearLayout form = new LinearLayout(getContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(20);
        form.setPadding(margin, 0, margin, 0);
        EditText key = new EditText(getContext());
        key.setHint("NAME"); // the variable-name placeholder is an identifier format, not prose
        key.setSingleLine(true);
        key.setTextColor(color(R.color.local_games_on_surface));
        key.setHintTextColor(color(R.color.local_games_on_surface_secondary));
        form.addView(key);
        EditText value = new EditText(getContext());
        value.setHint(R.string.local_game_runtime_env_value_hint);
        value.setSingleLine(false);
        value.setTextColor(color(R.color.local_games_on_surface));
        value.setHintTextColor(color(R.color.local_games_on_surface_secondary));
        form.addView(value);
        new FrostedDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(R.string.local_game_runtime_env_add_title)
            .setView(scrollable(form))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_game_runtime_env_add_action, (dialog, which) -> {
                String name = key.getText().toString().trim();
                String optionValue = value.getText().toString();
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || optionValue.isEmpty()) {
                    Toast.makeText(getContext(), R.string.local_game_runtime_env_invalid,
                        Toast.LENGTH_LONG).show();
                    return;
                }
                setOption(name, optionValue);
                render();
            })
            .show();
    }

    private void addComponentVersionRow(String label, CustomRuntimeComponent.Kind kind, String current,
            @Nullable RuntimeTranslator locked, ChoiceCallback callback) {
        addRow(label, componentLabel(current), true, view -> openVersions(kind, current, locked, callback));
    }

    /** Handles an Activity result even when Android recreated the host while the picker was open. */
    public void restoreComponentSelection(Intent data) {
        if (profile == null) { restoredComponentSelection = data; return; }
        try {
            GameRuntimeBackendType backend = GameRuntimeBackendType.fromStorageValue(
                data.getStringExtra(RuntimeComponentVersionsActivity.EXTRA_BACKEND));
            CustomRuntimeComponent.Kind kind = CustomRuntimeComponent.Kind.valueOf(
                data.getStringExtra(RuntimeComponentVersionsActivity.EXTRA_KIND));
            String id = data.getStringExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID);
            if (id == null || backend != profile.getRuntimeBackendType()) throw new IllegalArgumentException("component_scope_mismatch");
            switch (kind) {
                case WINE:
                    replace(id, profile.getGraphicsDriver(), profile.getDxWrapper(), profile.getAudioDriver(),
                        profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(), profile.getInputProfileId(), profile.getLaunchExecutionMode()); break;
                case DXVK: case VKD3D:
                    replace(profile.getWinePackage(), profile.getGraphicsDriver(), id, profile.getAudioDriver(),
                        profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(), profile.getInputProfileId(), profile.getLaunchExecutionMode()); break;
                case TURNIP:
                    replace(profile.getWinePackage(), id, profile.getDxWrapper(), profile.getAudioDriver(),
                        profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(), profile.getInputProfileId(), profile.getLaunchExecutionMode()); break;
                case BOX64: setOption("GAMES_CUSTOM_BOX64_ID", id); break;
            }
            render();
        } catch (RuntimeException error) {
            Toast.makeText(getContext(), getContext().getString(R.string.component_versions_failed,
                error.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void openVersions(CustomRuntimeComponent.Kind kind, String current,
            @Nullable RuntimeTranslator locked, ChoiceCallback callback) {
        listener.onSelectRuntimeComponent(RuntimeComponentVersionsActivity.createSelectIntent(
            getContext(), kind, profile.getRuntimeBackendType(), current, locked), value -> {
                callback.choose(value);
                ioExecutor.execute(() -> {
                    try {
                        List<CustomRuntimeComponent> entries = customComponents.list();
                        List<RuntimeComponentVariant> variants = LocalGames.requireHost(getContext())
                            .listInstalledRuntimeComponentVariants(kind, profile.getRuntimeBackendType());
                        post(() -> {
                            if (destroyed) return;
                            customEntries = entries;
                            List<RuntimeComponentVariant> next = new ArrayList<>(installedVariants);
                            next.removeIf(entry -> entry.getKind() == kind);
                            next.addAll(variants);
                            installedVariants = next;
                            render();
                        });
                    } catch (IOException error) {
                        post(() -> Toast.makeText(getContext(), getContext().getString(
                            R.string.component_versions_failed, error.getMessage()), Toast.LENGTH_LONG).show());
                    }
                });
                render();
            });
    }

    private String componentLabel(String value) {
        if (value == null || value.isEmpty()) return s(R.string.component_versions_default_translator);
        for (CustomRuntimeComponent entry : customEntries) if (entry.getId().equals(value)) return entry.getDisplayName();
        for (RuntimeComponentVariant entry : installedVariants) if (entry.getId().equals(value)) return entry.getDisplayName();
        if (componentIndex != null && componentIndex.find(value).isPresent()) return componentIndex.find(value).get().getDisplayName();
        return value;
    }

    private boolean usesVulkanWrapper() {
        String dx = profile.getDxWrapper();
        return !"wined3d".equals(dx) && !"rootfs-wined3d".equals(dx);
    }

    private void showGraphicsFamily(boolean rootfs) {
        String[] labels = rootfs ? new String[] {"VirGL", "LLVMpipe"} : new String[] {"Turnip", "VirGL"};
        new FrostedDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(R.string.local_game_runtime_common_graphics_driver)
            .setItems(labels, (dialog, which) -> {
                if (!rootfs && which == 0) {
                    openVersions(CustomRuntimeComponent.Kind.TURNIP, profile.getGraphicsDriver(), null,
                        value -> replace(profile.getWinePackage(), value, profile.getDxWrapper(), profile.getAudioDriver(),
                            profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(),
                            profile.getInputProfileId(), profile.getLaunchExecutionMode()));
                    return;
                }
                boolean virgl = !rootfs || which == 0;
                if (virgl && usesVulkanWrapper()) {
                    Toast.makeText(getContext(), R.string.component_versions_virgl_dxvk, Toast.LENGTH_LONG).show();
                    return;
                }
                String value = rootfs ? (virgl ? "rootfs-virgl-mesa" : "rootfs-llvmpipe") : "virgl";
                replace(profile.getWinePackage(), value, profile.getDxWrapper(), profile.getAudioDriver(),
                    profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(),
                    profile.getInputProfileId(), profile.getLaunchExecutionMode());
                render();
            }).show();
    }

    private void showDxFamily(boolean rootfs) {
        String[] labels = rootfs ? new String[] {"DXVK", "WineD3D"} : new String[] {"DXVK", "VKD3D", "WineD3D"};
        new FrostedDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(R.string.local_game_runtime_common_dx)
            .setItems(labels, (dialog, which) -> {
                ChoiceCallback selected = value -> replace(profile.getWinePackage(), profile.getGraphicsDriver(), value,
                    profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(),
                    profile.getInputProfileId(), profile.getLaunchExecutionMode());
                if (which == labels.length - 1) {
                    selected.choose(rootfs ? "rootfs-wined3d" : "wined3d"); render(); return;
                }
                if (profile.getGraphicsDriver().contains("virgl")) {
                    Toast.makeText(getContext(), R.string.component_versions_vulkan_required, Toast.LENGTH_LONG).show();
                    return;
                }
                openVersions(which == 0 ? CustomRuntimeComponent.Kind.DXVK : CustomRuntimeComponent.Kind.VKD3D,
                    profile.getDxWrapper(), null, selected);
            }).show();
    }

    private static String firstLine(File file) throws IOException {
        if (!file.isFile()) return "";
        try (java.io.BufferedReader input = new java.io.BufferedReader(new java.io.FileReader(file))) {
            String line = input.readLine();
            return line == null ? "" : line.trim();
        }
    }

    private RuntimeTranslator lockedRootfsTranslator() {
        if (profile.getRuntimeBackendType() != GameRuntimeBackendType.ROOTFS_PROOT
            || GameContainer.DEFAULT_ID.equals(profile.getContainerId())) {
            return null;
        }
        File prefix = paths.getContainerPrefixDirectory(profile.getContainerId(),
            GameRuntimeBackendType.ROOTFS_PROOT);
        if (!new File(prefix, "system.reg").isFile()) return null;
        try {
            String recorded = firstLine(new File(prefix, ".games-runtime/translator"));
            for (RuntimeTranslator translator : RuntimeTranslator.values()) {
                if (translator.getStorageValue().equals(recorded)) return translator;
            }
            String marker = firstLine(new File(prefix, ".games-runtime/runtime"));
            int separator = marker.indexOf('|');
            if (separator >= 0 && separator + 1 < marker.length()) {
                return GameContainerProfileResolver.resolveTranslator(marker.substring(separator + 1), paths);
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable markers: do not restrict the choice.
        }
        return null;
    }

    private void renderAdvanced() {
        pageTitle(s(R.string.local_game_runtime_menu_advanced));
        addChoice(s(R.string.local_game_runtime_advanced_box64_preset), withCurrent(profile.getBox64Preset(), "STABILITY", "INTERMEDIATE", "PERFORMANCE"),
            value -> replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
                profile.getAudioDriver(), profile.getResolution(), value, profile.getEnvironment(),
                profile.getInputProfileId(), profile.getLaunchExecutionMode()));
        RuntimeTranslator translator = profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT
            ? lockedRootfsTranslator() : RuntimeTranslator.BOX64;
        if (translator == RuntimeTranslator.BOX64 || translator == null) {
            addComponentVersionRow(s(R.string.local_game_runtime_advanced_box64_build), CustomRuntimeComponent.Kind.BOX64,
                option("GAMES_CUSTOM_BOX64_ID"), null, value -> setOption("GAMES_CUSTOM_BOX64_ID", value));
        }
        addChoice(s(R.string.local_game_runtime_advanced_startup),
            labels(s(R.string.local_game_runtime_advanced_app_shell),
                s(R.string.local_game_runtime_advanced_terminal)),
            profile.getLaunchExecutionMode() == LaunchExecutionMode.APP_SHELL ? 0 : 1,
            which -> replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
                profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(),
                profile.getEnvironment(), profile.getInputProfileId(), which == 0
                    ? LaunchExecutionMode.APP_SHELL : LaunchExecutionMode.TERMINAL_SESSION));
        addChoice(s(R.string.local_game_runtime_advanced_windows_version), labels("Windows 7", "Windows 10", "Windows 11"),
            choiceIndex(option("GAMES_WINDOWS_VERSION"), "Windows 7", "Windows 10", "Windows 11"),
            which -> setOption("GAMES_WINDOWS_VERSION",
                labels("Windows 7", "Windows 10", "Windows 11")[which]));
        addCpuCoreSelector(s(R.string.local_game_runtime_advanced_cpu_cores), "GAMES_CPU_CORES");
        addCpuCoreSelector(s(R.string.local_game_runtime_advanced_cpu_cores_32), "GAMES_CPU_CORES_32");
    }

    private void pageTitle(String value) {
        title.setText(value);
        if (header.getVisibility() != View.VISIBLE) {
            LinearLayout headingBlock = new LinearLayout(getContext());
            headingBlock.setOrientation(LinearLayout.VERTICAL);
            TextView heading = text(value, 20, R.color.local_games_on_surface);
            heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            headingBlock.addView(heading);
            headingBlock.addView(text(backendSubtitle.getText().toString(), 12,
                R.color.local_games_on_surface_secondary));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(4), dp(4), dp(4), dp(14));
            content.addView(headingBlock, params);
        }
    }

    private void addMenu(String label, String summary, Page target) {
        addRow(label, summary, true, view -> { page = target; render(); });
    }

    private void addReadOnly(String label, String summary) {
        addRow(label, summary, false, null);
    }

    private void addChoice(String label, String[] values, ChoiceCallback callback) {
        int checked = indexOf(values, valueFor(label));
        addChoice(label, values, checked, which -> callback.choose(values[which]));
    }

    private void addChoice(String label, String[] values, int checked, IndexCallback callback) {
        String current = checked >= 0 && checked < values.length ? values[checked]
            : s(R.string.local_game_runtime_not_selected);
        addRow(label, current, true, view -> new FrostedDialogBuilder(getContext(),
            R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(label)
            .setSingleChoiceItems(values, checked, (dialog, which) -> {
                dialog.dismiss();
                callback.choose(which);
                render();
            })
            .show());
    }

    private String valueFor(String label) {
        if (s(R.string.local_game_runtime_common_resolution).equals(label)) return profile.getResolution();
        if (s(R.string.local_game_runtime_common_graphics_driver).equals(label)) return profile.getGraphicsDriver();
        if (s(R.string.local_game_runtime_common_dx).equals(label)) return profile.getDxWrapper();
        if (s(R.string.local_game_runtime_common_wine_package).equals(label)) return profile.getWinePackage();
        if (s(R.string.local_game_runtime_common_audio_driver).equals(label)) return profile.getAudioDriver();
        if (s(R.string.local_game_runtime_advanced_box64_preset).equals(label)) return profile.getBox64Preset();
        return emptyAs(profile.getInputProfileId(), "default");
    }

    private void addEdit(String label, String value, boolean multiline, EditCallback callback) {
        addRow(label, value.isEmpty() ? s(R.string.local_game_runtime_env_none) : value.replace('\n', ' '), true, view -> {
            EditText input = new EditText(getContext());
            input.setText(value);
            input.setTextColor(color(R.color.local_games_on_surface));
            input.setHintTextColor(color(R.color.local_games_on_surface_secondary));
            input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT);
            input.setMinLines(multiline ? 6 : 1);
            new FrostedDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
                .setTitle(label)
                .setView(scrollable(input))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_game_runtime_env_apply, (dialog, which) -> {
                    callback.edit(input.getText().toString());
                    render();
                })
                .show();
        });
    }

    private void addCpuCoreSelector(String label, String key) {
        int coreCount = Math.max(1, Runtime.getRuntime().availableProcessors());
        String[] cores = new String[coreCount];
        boolean[] selected = selectedCores(option(key), coreCount);
        for (int index = 0; index < coreCount; index++) cores[index] = "CPU" + index;
        String current = selectedCoreSummary(selected);
        addRow(label, current, true, view -> new FrostedDialogBuilder(getContext(),
            R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(label)
            .setMultiChoiceItems(cores, selected, (dialog, which, checked) -> selected[which] = checked)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_game_runtime_env_apply, (dialog, which) -> {
                setOption(key, selectedCoreList(selected));
                render();
            })
            .show());
    }

    private void addDllChoice(String label, String key) {
        String[] values = labels(s(R.string.local_game_runtime_dll_builtin),
            s(R.string.local_game_runtime_dll_native),
            s(R.string.local_game_runtime_dll_native_builtin));
        addChoice(label, values, choiceIndex(option(key), values),
            which -> setOption(key, new String[] { "builtin", "native", "native,builtin" }[which]));
    }

    private String option(String key) {
        String value = profile.getEnvironment().get(key);
        return value == null ? "" : value;
    }

    private void setOption(String key, String value) {
        Map<String, String> environment = new LinkedHashMap<>(profile.getEnvironment());
        if (value == null || value.trim().isEmpty()) environment.remove(key);
        else environment.put(key, value.trim());
        replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
            profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(), environment,
            profile.getInputProfileId(), profile.getLaunchExecutionMode());
    }

    private static int choiceIndex(String value, String... labels) {
        if (value == null || value.isEmpty()) return 0;
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equalsIgnoreCase(value)) return index;
        }
        if ("light".equalsIgnoreCase(value)) return 0;
        if ("dark".equalsIgnoreCase(value)) return Math.min(1, labels.length - 1);
        if ("builtin".equalsIgnoreCase(value)) return 0;
        if ("native".equalsIgnoreCase(value)) return Math.min(1, labels.length - 1);
        if ("native,builtin".equalsIgnoreCase(value)) return Math.min(2, labels.length - 1);
        return 0;
    }

    private static int localeChoiceIndex(String value) {
        if ("en_US.UTF-8".equalsIgnoreCase(value) || "en_US.utf8".equalsIgnoreCase(value)) return 1;
        if ("zh_CN.UTF-8".equalsIgnoreCase(value) || "zh_CN.utf8".equalsIgnoreCase(value)) return 2;
        return 0;
    }

    private static boolean[] selectedCores(String value, int coreCount) {
        boolean[] selected = new boolean[coreCount];
        if (value == null || value.trim().isEmpty()) {
            for (int core = 0; core < coreCount; core++) selected[core] = true;
            return selected;
        }
        for (String segment : value.split(",")) {
            String[] bounds = segment.trim().split("-", 2);
            try {
                int first = Integer.parseInt(bounds[0].trim());
                int last = bounds.length == 2 ? Integer.parseInt(bounds[1].trim()) : first;
                for (int core = Math.max(0, first); core <= Math.min(coreCount - 1, last); core++) {
                    selected[core] = true;
                }
            } catch (NumberFormatException ignored) {
                // Ignore obsolete manual entries outside the selectable CPU list.
            }
        }
        return selected;
    }

    private static String selectedCoreList(boolean[] selected) {
        StringBuilder value = new StringBuilder();
        for (int core = 0; core < selected.length; core++) {
            if (!selected[core]) continue;
            if (value.length() > 0) value.append(',');
            value.append(core);
        }
        return value.toString();
    }

    private String selectedCoreSummary(boolean[] selected) {
        String value = selectedCoreList(selected);
        if (value.isEmpty()) return s(R.string.local_game_runtime_advanced_no_cores);
        for (boolean core : selected) if (!core) return value;
        return s(R.string.local_game_runtime_advanced_all_cores);
    }

    private void addRow(String label, String summary, boolean interactive, @Nullable View.OnClickListener listener) {
        FrostedMenuRow.add(getContext(), content, label, summary, 2, interactive, listener);
    }

    private void addHint(String value) {
        TextView hint = text(value, 14, R.color.local_games_on_surface_secondary);
        hint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(4), dp(4), dp(4), dp(16));
        content.addView(hint, params);
    }

    private void replace(String wine, String graphics, String dx, String audio, String resolution,
                         String box64, Map<String, String> environment, String input,
                         LaunchExecutionMode mode) {
        profile = new RuntimeProfile(profile.getId(), wine, graphics, dx, audio, resolution, box64,
            environment, input, mode, profile.getComponentVersions(), profile.getRuntimeBackendType(),
            profile.getRootfsPackage(), profile.getContainerId());
    }

    public void navigateBack() {
        if (page != Page.ROOT) {
            page = Page.ROOT;
            render();
            return;
        }
        boolean profileChanged = baseline != null && !RuntimeProfileDiff.between(baseline, profile).isEmpty();
        android.util.Log.d("GameRuntimeOptionsDebug", "navigateBack at ROOT profileChanged="
            + profileChanged + " gameChanged=" + gameChanged() + " baseline.backend="
            + (baseline == null ? "null" : baseline.getRuntimeBackendType())
            + " profile.backend=" + profile.getRuntimeBackendType());
        if (profileChanged || gameChanged()) {
            new FrostedDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
                .setTitle(R.string.local_game_runtime_save_dialog_title)
                .setMessage(getContext().getString(R.string.local_game_runtime_save_dialog_message,
                    game.getName()))
                .setNegativeButton(R.string.local_game_runtime_save_dialog_discard,
                    (dialog, which) -> close())
                .setNeutralButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.local_game_runtime_save, (dialog, which) -> saveAndFinish())
                .show();
            return;
        }
        close();
    }

    private boolean gameChanged() {
        return gameBaseline != null && (!gameBaseline.getName().equals(game.getName()) ||
            !gameBaseline.getWorkingDirectory().equals(game.getWorkingDirectory()) ||
            !gameBaseline.getArguments().equals(game.getArguments()));
    }

    private void saveAndFinish() {
        if (saving || profile == null || game == null) return;
        saving = true;
        saveFab.setEnabled(false);
        RuntimeProfile toSave = profile;
        Game gameToSave = game;
        boolean saveGame = gameChanged();
        boolean rootfs = toSave.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT;
        android.util.Log.d("GameRuntimeOptionsDebug", "saveAndFinish toSave.backend="
            + toSave.getRuntimeBackendType() + " containerId=" + toSave.getContainerId());
        ioExecutor.execute(() -> {
            try {
                com.termux.ui.wine.importer.GameWorkingDirectory.relative(resolvedGameRoot(),
                    com.termux.ui.wine.importer.GameWorkingDirectory.display(resolvedGameRoot(), gameToSave.getWorkingDirectory()));
                com.termux.ui.wine.api.WineCDriveBackupTasks.requireAvailable(getContext().getFilesDir());
                profileRepository.save(toSave);
                if (saveGame) gameRepository.save(gameToSave);
                String warmupTaskId = RuntimeWarmup.warm(getContext(), toSave);
                post(() -> {
                    if (!destroyed) listener.onRuntimeOptionsSaved(warmupTaskId, rootfs);
                });
            } catch (IOException | RuntimeException error) {
                android.util.Log.e("GameRuntimeOptionsDebug", "save failed", error);
                post(() -> {
                    if (destroyed) return;
                    saving = false;
                    saveFab.setEnabled(true);
                    Toast.makeText(getContext(), getContext().getString(R.string.local_game_detail_save_failed,
                        error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()),
                        Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    public void release() {
        destroyed = true;
        ioExecutor.shutdownNow();
    }

    /** Embedded landscape panels use the device back key instead of a second navigation bar. */
    public void setShowHeader(boolean show) {
        int visibility = show ? View.VISIBLE : View.GONE;
        if (header.getVisibility() == visibility) return;
        header.setVisibility(visibility);
        if (profile != null && game != null) {
            int scrollY = optionsScroll.getScrollY();
            render();
            optionsScroll.post(() -> optionsScroll.scrollTo(0, scrollY));
        }
    }

    private void close() {
        if (listener != null) listener.onRuntimeOptionsClosed();
    }

    private TextView text(String value, float sizeSp, int colorRes) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(colorRes));
        return view;
    }

    /** Wraps a dialog body so it scrolls instead of being clipped in a short (landscape / IME)
     *  window; the dialog window itself is bounded by the platform, not by a fixed pixel size. */
    private ScrollView scrollable(View body) {
        ScrollView scroll = new ScrollView(getContext());
        scroll.setFillViewport(false);
        scroll.addView(body);
        return scroll;
    }

    private String s(int resource) { return getContext().getString(resource); }

    private void applySaveFabMargins() {
        int margin = getResources().getDimensionPixelSize(R.dimen.local_game_runtime_fab_margin);
        saveFabParams.setMargins(margin, margin, margin + saveFabInsetEnd,
            margin + saveFabInsetBottom);
        if (saveFab != null) saveFab.setLayoutParams(saveFabParams);
    }

    /** The host keeps this view across rotation (no recreation), so everything that was sized in
     *  dp from the display density is refreshed here; the page, the edits and open dialogs stay. */
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        float density = getResources().getDisplayMetrics().density;
        header.setPadding(dp(4), 0, dp(4), 0);
        header.setMinimumHeight(getResources().getDimensionPixelSize(R.dimen.local_games_toolbar_height));
        int pageInset = getResources().getDimensionPixelSize(R.dimen.local_games_page_horizontal_padding);
        content.setPadding(pageInset, dp(8), pageInset,
            getResources().getDimensionPixelSize(R.dimen.local_game_runtime_save_bar_clearance));
        applySaveFabMargins();
        if (density != lastDensity) {
            lastDensity = density;
            if (profile != null && game != null) {
                // Rows were built with the old density: rebuild them, keeping the scroll position.
                int scrollY = optionsScroll.getScrollY();
                render();
                optionsScroll.post(() -> optionsScroll.scrollTo(0, scrollY));
            }
        }
    }

    private int color(int resource) { return ContextCompat.getColor(getContext(), resource); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static String[] labels(String... values) { return values; }
    private static String emptyAs(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
    private static int indexOf(String[] values, String value) {
        for (int index = 0; index < values.length; index++) if (values[index].equals(value)) return index;
        return 0;
    }
    private static String[] withCurrent(String current, String... defaults) {
        List<String> values = new ArrayList<>();
        values.add(current);
        for (String value : defaults) if (!values.contains(value)) values.add(value);
        return values.toArray(new String[0]);
    }
    private static String environmentText(Map<String, String> environment) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : environment.entrySet()) {
            if (text.length() > 0) text.append('\n');
            text.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }
    private static Map<String, String> parseEnvironment(String value) {
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String line : value.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            int delimiter = trimmed.indexOf('=');
            if (delimiter <= 0) throw new IllegalArgumentException("invalid environment entry");
            parsed.put(trimmed.substring(0, delimiter).trim(), trimmed.substring(delimiter + 1));
        }
        return parsed;
    }

    private interface ChoiceCallback { void choose(String value); }
    private interface IndexCallback { void choose(int value); }
    private interface EditCallback { void edit(String value); }
}
