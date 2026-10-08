package com.termux.localgames.activity;

import android.content.Context;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.localgames.R;
import com.termux.localgames.api.CustomComponentInstallTasks;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.RuntimeWarmup;
import com.termux.localgames.data.FileCustomRuntimeComponentRepository;
import com.termux.localgames.data.FileCustomComponentInstallTaskRepository;
import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameContainerRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.RuntimeProfileRepository;
import com.termux.localgames.domain.CustomComponentInstallTask;
import com.termux.localgames.domain.CustomComponentInstallTaskState;
import com.termux.localgames.domain.CustomRuntimeComponent;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfileDiff;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.domain.RuntimeTranslator;
import com.termux.localgames.importer.LaunchArguments;
import com.termux.localgames.runtime.GameContainerFactory;
import com.termux.localgames.runtime.GameContainerProfileResolver;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private GameContainerRepository containerRepository;
    private FileCustomRuntimeComponentRepository customComponents;
    private GameStoragePaths paths;
    private List<CustomRuntimeComponent> customWineEntries = Collections.emptyList();
    private List<CustomRuntimeComponent> customBox64Entries = Collections.emptyList();
    private LinearLayout content;
    private TextView title;
    private LinearLayout header;
    private ProgressBar progress;
    private LinearLayout progressHost;
    private ScrollView optionsScroll;
    private final String gameId;
    private final Listener listener;
    private Game game;
    private Game gameBaseline;
    private RuntimeProfile baseline;
    private RuntimeProfile profile;
    private Page page = Page.ROOT;
    private boolean destroyed;

    private enum Page {
        ROOT, GENERAL, CONTAINER, BASE, WINE, LIBRARIES, ENVIRONMENT, FOLDERS, ADVANCED
    }

    public interface Listener {
        void onRuntimeOptionsClosed();
        /** Called instead of {@link #onRuntimeOptionsClosed()} after a successful Save --
         *  hosts should navigate to the Library tab and, if {@code warmupTaskId} is non-null,
         *  surface that task's live setup console there. */
        void onRuntimeOptionsSaved(@Nullable String warmupTaskId, boolean rootfs);
        /** This view is a plain lazily-created LinearLayout, not an Activity/Fragment, so it
         *  cannot register an ActivityResultLauncher itself (must happen before STARTED). The
         *  host must launch a SAF OpenDocument picker with the given MIME types and invoke the
         *  supplied callback with the picked Uri (or null if the user cancelled) -- used by the
         *  "+ Add custom Wine/Box64 build..." flow. */
        void onPickCustomComponentFile(String[] mimeTypes, Consumer<Uri> onPicked);
    }

    public GameRuntimeOptionsView(Context context, String gameId, Listener listener) {
        super(context);
        this.gameId = gameId;
        this.listener = listener;
        this.paths = new GameStoragePaths(getContext().getFilesDir());
        gameRepository = new FileGameRepository(paths.getLibraryDirectory());
        profileRepository = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        containerRepository = new FileGameContainerRepository(paths.getContainersDirectory());
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
        LinearLayout root = new LinearLayout(getContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(color(R.color.local_games_background));

        header = new LinearLayout(getContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), 0, dp(16), 0);
        header.setMinimumHeight(dp(48));
        TextView back = text("‹", 34, R.color.local_games_on_surface);
        back.setGravity(Gravity.CENTER);
        header.addView(back, new LinearLayout.LayoutParams(dp(34), dp(48)));
        back.setOnClickListener(view -> navigateBack());
        title = text("Runtime parameters", 18, R.color.local_games_on_surface);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        root.addView(header);

        progress = new ProgressBar(getContext());
        progressHost = new LinearLayout(getContext());
        progressHost.setGravity(Gravity.CENTER);
        progressHost.addView(progress);
        root.addView(progressHost, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        optionsScroll = new ScrollView(getContext());
        optionsScroll.setFillViewport(true);
        content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(8), dp(20), dp(28));
        optionsScroll.addView(content);
        optionsScroll.setVisibility(View.GONE);
        root.addView(optionsScroll, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void load() {
        ioExecutor.execute(() -> {
            try {
                Optional<Game> foundGame = gameRepository.find(gameId);
                if (!foundGame.isPresent()) throw new IOException("game_not_found");
                RuntimeProfile loaded = profileRepository.find(gameId).orElseGet(() ->
                    RuntimeProfilePresets.create(gameId, RuntimeProfilePreset.RECOMMENDED));
                List<CustomRuntimeComponent> wineEntries = loadCustomEntries(CustomRuntimeComponent.Kind.WINE);
                List<CustomRuntimeComponent> box64Entries = loadCustomEntries(CustomRuntimeComponent.Kind.BOX64);
                post(() -> {
                    if (destroyed) return;
                    game = foundGame.get();
                    gameBaseline = game;
                    baseline = loaded;
                    profile = loaded;
                    customWineEntries = wineEntries;
                    customBox64Entries = box64Entries;
                    progressHost.setVisibility(View.GONE);
                    optionsScroll.setVisibility(View.VISIBLE);
                    render();
                });
            } catch (IOException | RuntimeException error) {
                post(() -> {
                    if (!destroyed) {
                        Toast.makeText(getContext(), "Unable to load runtime parameters", Toast.LENGTH_LONG).show();
                        close();
                    }
                });
            }
        });
    }

    private List<CustomRuntimeComponent> loadCustomEntries(CustomRuntimeComponent.Kind kind) {
        try {
            return customComponents.listByKind(kind);
        } catch (IOException error) {
            return Collections.emptyList();
        }
    }

    /** Reloads the custom-component registry and re-renders -- called after the add-custom flow's
     *  console dialog is dismissed, so a newly-installed entry appears in the picker immediately. */
    private void reloadCustomComponentsAndRender() {
        ioExecutor.execute(() -> {
            List<CustomRuntimeComponent> wineEntries = loadCustomEntries(CustomRuntimeComponent.Kind.WINE);
            List<CustomRuntimeComponent> box64Entries = loadCustomEntries(CustomRuntimeComponent.Kind.BOX64);
            post(() -> {
                if (destroyed) return;
                customWineEntries = wineEntries;
                customBox64Entries = box64Entries;
                render();
            });
        });
    }

    private void render() {
        content.removeAllViews();
        switch (page) {
            case GENERAL: renderGeneral(); break;
            case CONTAINER: renderContainer(); break;
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
        title.setText(game.getName() + " · Runtime");
        addMenu("General", game.getWorkingDirectory() + " · " + game.getExecutable(), Page.GENERAL);
        addMenu("Runtime backend", runtimeBackendLabel(profile.getRuntimeBackendType()), Page.CONTAINER);
        addMenu("Common", profile.getResolution() + " · "
            + profile.getGraphicsDriver() + " · " + profile.getDxWrapper(), Page.BASE);
        addMenu("Wine configuration", "Theme, background, font, DPI and mouse overlay", Page.WINE);
        addMenu("Function libraries", "Direct3D, DirectSound, DirectMusic, DirectShow and DirectPlay",
            Page.LIBRARIES);
        addMenu("Environment variables", profile.getEnvironment().isEmpty() ? "No overrides" :
            profile.getEnvironment().size() + " overrides", Page.ENVIRONMENT);
        addMenu("Set folders", "Drive letters and target paths", Page.FOLDERS);
        addMenu("Advanced", "Box64, startup option, Windows version and CPU cores", Page.ADVANCED);
    }

    private void renderGeneral() {
        pageTitle("General");
        addEdit("Game name", game.getName(), false, value -> {
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) game = game.withLaunchDetails(trimmed,
                game.getWorkingDirectory(), game.getArguments());
        });
        addEdit("Working directory", game.getWorkingDirectory(), false, value -> {
            String trimmed = value.trim();
            game = game.withLaunchDetails(game.getName(), trimmed.isEmpty() ? "." : trimmed,
                game.getArguments());
        });
        addEdit("Launch arguments", LaunchArguments.format(game.getArguments()), false, value -> {
            try {
                game = game.withLaunchDetails(game.getName(), game.getWorkingDirectory(),
                    LaunchArguments.parse(value));
            } catch (IllegalArgumentException error) {
                Toast.makeText(getContext(), "Launch arguments are invalid", Toast.LENGTH_LONG).show();
            }
        });
        addReadOnly("Executable", game.getExecutable());
    }

    private void renderContainer() {
        pageTitle("Runtime backend");
        String[] backendLabels = { runtimeBackendLabel(GameRuntimeBackendType.GLIBC_TERMUX_BOX),
            runtimeBackendLabel(GameRuntimeBackendType.ROOTFS_PROOT) };
        int checked = profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT ? 1 : 0;
        addChoice("Runtime backend", backendLabels, checked, which -> {
            android.util.Log.d("GameRuntimeOptionsDebug", "backend choice which=" + which
                + " checked=" + checked);
            if (which == checked) return;
            try {
                if (which == 1) switchToRootfs(); else switchToGlibc();
                android.util.Log.d("GameRuntimeOptionsDebug", "after switch profile.backend="
                    + profile.getRuntimeBackendType() + " containerId=" + profile.getContainerId());
            } catch (RuntimeException error) {
                android.util.Log.e("GameRuntimeOptionsDebug", "switch backend failed", error);
            }
        });
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
            addReadOnly("Rootfs package", profile.getRootfsPackage());
        }
        addReadOnly("Container id", profile.getContainerId());
        addRow("Components", "Manage installed runtime components", true, view ->
            getContext().startActivity(LocalGames.createComponentsIntent(getContext(), game.getId())));
        addRow("Backup runtime", "Export or restore GLIBC / RootFS runtimes", true, view ->
            getContext().startActivity(LocalGames.createRuntimeBackupIntent(getContext())));
    }

    private String runtimeBackendLabel(GameRuntimeBackendType type) {
        return getContext().getString(type == GameRuntimeBackendType.ROOTFS_PROOT
            ? R.string.local_game_runtime_profile_backend_rootfs
            : R.string.local_game_runtime_profile_backend_glibc);
    }

    /**
     * Rootfs only sticks if a real independent container is created and bound; leaving
     * containerId at "default" lets resolveContainer() silently force the backend back to
     * GLIBC on the next load or launch (the bug this page exists to fix).
     */
    private void switchToRootfs() {
        String containerId = "container-" + UUID.randomUUID().toString().substring(0, 8);
        RuntimeProfile draft = new RuntimeProfile(profile.getId(), "hangover-11.9",
            "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio", profile.getResolution(),
            profile.getBox64Preset(), profile.getEnvironment(), profile.getInputProfileId(),
            profile.getLaunchExecutionMode(), profile.getComponentVersions(),
            GameRuntimeBackendType.ROOTFS_PROOT, GameContainer.ROOTFS_RUNTIME_PACKAGE, containerId);
        android.util.Log.d("GameRuntimeOptionsDebug", "switchToRootfs draft built, containerId="
            + containerId);
        GameContainer container = GameContainerFactory.fromProfile(draft, paths);
        android.util.Log.d("GameRuntimeOptionsDebug", "container built id=" + container.getId()
            + " backend=" + container.getBackendType());
        try {
            containerRepository.save(container);
            android.util.Log.d("GameRuntimeOptionsDebug", "container saved to repository");
        } catch (IOException error) {
            android.util.Log.e("GameRuntimeOptionsDebug", "containerRepository.save failed", error);
            Toast.makeText(getContext(), "Unable to create rootfs container", Toast.LENGTH_LONG).show();
            return;
        }
        profile = new GameContainerProfileResolver().resolve(draft, container, paths);
        android.util.Log.d("GameRuntimeOptionsDebug", "profile field reassigned, backend="
            + profile.getRuntimeBackendType());
    }

    private void switchToGlibc() {
        profile = profile.withRuntimeBackend(GameRuntimeBackendType.GLIBC_TERMUX_BOX, "")
            .withContainerId(GameContainer.DEFAULT_ID);
    }

    private void renderBase() {
        pageTitle("Common");
        addChoice("Game resolution", withCurrent(profile.getResolution(),
                "960x540", "1280x720", "1600x900", "1920x1080"), value ->
            replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
                profile.getAudioDriver(), value, profile.getBox64Preset(), profile.getEnvironment(),
                profile.getInputProfileId(), profile.getLaunchExecutionMode()));
        boolean rootfs = profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT;
        String[] graphicsChoices = rootfs
            ? withCurrent(profile.getGraphicsDriver(), "rootfs-virgl-mesa", "rootfs-llvmpipe")
            : withCurrent(profile.getGraphicsDriver(), "turnip", "virgl", "llvmpipe");
        addChoice("Graphics driver", graphicsChoices,
            value -> {
                // VirGL has no Vulkan path, so it cannot host DXVK (RootfsProotBackend.
                // requireProfile() rejects that combination) -- fall back to WineD3D instead of
                // saving a profile that will only fail later, at launch preflight.
                String dx = profile.getDxWrapper();
                if (rootfs && "rootfs-virgl-mesa".equals(value)
                    && dx != null && dx.startsWith("rootfs-dxvk-")) {
                    dx = "rootfs-wined3d";
                    Toast.makeText(getContext(),
                        "VirGL does not support DXVK; switched DirectX translation to WineD3D",
                        Toast.LENGTH_LONG).show();
                }
                replace(profile.getWinePackage(), value, dx, profile.getAudioDriver(),
                    profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(),
                    profile.getInputProfileId(), profile.getLaunchExecutionMode());
            });
        String[] dxChoices;
        if (rootfs) {
            // DXVK 2.7 first: Vulkan 1.3, best fit for the software (llvmpipe) renderer. 3.1 needs
            // Vulkan 1.4; 1.10.3 has the lowest Vulkan floor. VirGL has no Vulkan, so only WineD3D.
            dxChoices = "rootfs-virgl-mesa".equals(profile.getGraphicsDriver())
                ? withCurrent(profile.getDxWrapper(), "rootfs-wined3d")
                : withCurrent(profile.getDxWrapper(), "rootfs-wined3d",
                    "rootfs-dxvk-2.7", "rootfs-dxvk-3.1", "rootfs-dxvk-1.10.3");
        } else {
            dxChoices = withCurrent(profile.getDxWrapper(), "dxvk", "vkd3d", "wined3d");
        }
        addChoice("DirectX translation", dxChoices,
            value -> replace(profile.getWinePackage(), profile.getGraphicsDriver(), value,
                profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(),
                profile.getEnvironment(), profile.getInputProfileId(), profile.getLaunchExecutionMode()));
        if (rootfs) {
            // hangover-11.9 bundles its own translation layer inside the wine binary (apt-installed,
            // shared system-wide). box64-wine-10.0 is a portable vanilla wine run under the
            // standalone Box64 translator (see rootfs_prefix_warmup.sh's resolve_rootfs_translator) --
            // an independent runtime, selectable per game. Custom-installed builds (see
            // showAddCustomComponentFlow) are appended after the two presets, and a trailing
            // "+ Add custom Wine build..." row launches that flow instead of selecting a value.
            addChoiceWithAddCustom("Wine package", profile.getWinePackage(),
                new String[] {"hangover-11.9", "box64-wine-10.0"}, customWineEntries, null,
                getContext().getString(R.string.local_games_custom_component_add_wine),
                value -> replace(value, profile.getGraphicsDriver(), profile.getDxWrapper(),
                    profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(),
                    profile.getEnvironment(), profile.getInputProfileId(),
                    profile.getLaunchExecutionMode()),
                () -> showAddCustomComponentFlow(CustomRuntimeComponent.Kind.WINE));
        }
        addChoice("Audio driver", withCurrent(profile.getAudioDriver(), "alsa", "pulseaudio"),
            value -> replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
                value, profile.getResolution(), profile.getBox64Preset(), profile.getEnvironment(),
                profile.getInputProfileId(), profile.getLaunchExecutionMode()));
        addChoice("Show FPS", labels("Off", "On"), "1".equals(option("GAMES_SHOW_FPS")) ? 1 : 0,
            which -> setOption("GAMES_SHOW_FPS", which == 1 ? "1" : "0"));
    }

    private void renderWine() {
        pageTitle("Wine configuration");
        addChoice("Language", labels("Runtime default", "English", "Chinese (Simplified)"),
            localeChoiceIndex(option("GAMES_LOCALE")), which -> setOption("GAMES_LOCALE",
                new String[] { "", "en_US.UTF-8", "zh_CN.UTF-8" }[which]));
        addChoice("Theme", labels("Light", "Dark"), choiceIndex(option("GAMES_WINE_THEME"),
            "Light", "Dark"), which -> setOption("GAMES_WINE_THEME", which == 0 ? "light" : "dark"));
        addEdit("Background", option("GAMES_WINE_BACKGROUND"), false,
            value -> setOption("GAMES_WINE_BACKGROUND", value));
        addChoice("System font", labels("Tahoma", "Arial", "Segoe UI"),
            choiceIndex(option("GAMES_WINE_FONT"), "Tahoma", "Arial", "Segoe UI"),
            which -> setOption("GAMES_WINE_FONT", labels("Tahoma", "Arial", "Segoe UI")[which]));
        addChoice("DPI", labels("96", "120", "144", "192"),
            choiceIndex(option("GAMES_WINE_DPI"), "96", "120", "144", "192"),
            which -> setOption("GAMES_WINE_DPI", labels("96", "120", "144", "192")[which]));
        addChoice("Mouse overlay offset", labels("Disable", "Force"),
            "force".equals(option("GAMES_MOUSE_WARP")) ? 1 : 0,
            which -> setOption("GAMES_MOUSE_WARP", which == 1 ? "force" : "disable"));
    }

    private void renderLibraries() {
        pageTitle("Function libraries");
        addDllChoice("Direct3D", "GAMES_DLL_D3D");
        addDllChoice("DirectSound", "GAMES_DLL_DSOUND");
        addDllChoice("DirectMusic", "GAMES_DLL_DMUSIC");
        addDllChoice("DirectShow", "GAMES_DLL_DSHOW");
        addDllChoice("DirectPlay", "GAMES_DLL_DPLAY");
    }

    private void renderFolders() {
        pageTitle("Set folders");
        addEdit("D:", option("GAMES_DRIVE_D"), false, value -> setOption("GAMES_DRIVE_D", value));
        addEdit("E:", option("GAMES_DRIVE_E"), false, value -> setOption("GAMES_DRIVE_E", value));
    }

    private void renderEnvironment() {
        pageTitle("Environment");
        for (Map.Entry<String, String> entry : profile.getEnvironment().entrySet()) {
            addEnvironmentEntry(entry.getKey(), entry.getValue());
        }
        addRow("Add environment variable", "", true, view -> showAddEnvironmentVariable());
    }

    private void addEnvironmentEntry(String key, String value) {
        addRow(key, value, true, view -> {
            EditText input = new EditText(getContext());
            input.setText(value);
            input.setSingleLine(false);
            input.setTextColor(color(R.color.local_games_on_surface));
            new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
                .setTitle(key)
                .setView(input)
                .setNegativeButton("Delete", (dialog, which) -> {
                    setOption(key, "");
                    render();
                })
                .setNeutralButton(android.R.string.cancel, null)
                .setPositiveButton("Apply", (dialog, which) -> {
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
        key.setHint("NAME");
        key.setSingleLine(true);
        key.setTextColor(color(R.color.local_games_on_surface));
        key.setHintTextColor(color(R.color.local_games_on_surface_secondary));
        form.addView(key);
        EditText value = new EditText(getContext());
        value.setHint("Value");
        value.setSingleLine(false);
        value.setTextColor(color(R.color.local_games_on_surface));
        value.setHintTextColor(color(R.color.local_games_on_surface_secondary));
        form.addView(value);
        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle("Add environment variable")
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Add", (dialog, which) -> {
                String name = key.getText().toString().trim();
                String optionValue = value.getText().toString();
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || optionValue.isEmpty()) {
                    Toast.makeText(getContext(), "Variable name or value is invalid", Toast.LENGTH_LONG).show();
                    return;
                }
                setOption(name, optionValue);
                render();
            })
            .show();
    }

    /** Like addChoice, but appends registry-sourced custom entries after the presets and a
     *  trailing "+ Add custom build..." row whose selection launches onAddCustom instead of
     *  calling callback. currentValue is always values[0] (same convention as withCurrent), so
     *  indexOf's "not found -> 0" fallback still resolves to the actual current selection.
     *  emptyValueLabel is shown in place of the raw value when currentValue/a value is empty
     *  (the Box64 build row's "no override" case); pass null when the value is never empty
     *  (the Wine package row). */
    private void addChoiceWithAddCustom(String label, String currentValue, String[] presetValues,
            List<CustomRuntimeComponent> customEntries, @Nullable String emptyValueLabel,
            String addCustomLabel, ChoiceCallback callback, Runnable onAddCustom) {
        List<String> values = new ArrayList<>();
        values.add(currentValue);
        for (String preset : presetValues) if (!values.contains(preset)) values.add(preset);
        for (CustomRuntimeComponent entry : customEntries) {
            if (!values.contains(entry.getId())) values.add(entry.getId());
        }
        String[] valuesArray = values.toArray(new String[0]);
        String[] displayLabels = new String[valuesArray.length + 1];
        for (int index = 0; index < valuesArray.length; index++) {
            displayLabels[index] = displayNameFor(valuesArray[index], customEntries, emptyValueLabel);
        }
        displayLabels[valuesArray.length] = addCustomLabel;
        int checked = indexOf(valuesArray, currentValue);
        addRow(label, displayLabels[checked], true, view -> new MaterialAlertDialogBuilder(
            getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(label)
            .setSingleChoiceItems(displayLabels, checked, (dialog, which) -> {
                dialog.dismiss();
                if (which == valuesArray.length) {
                    onAddCustom.run();
                    return;
                }
                callback.choose(valuesArray[which]);
                render();
            })
            .show());
    }

    private static String displayNameFor(String value, List<CustomRuntimeComponent> customEntries,
            @Nullable String emptyValueLabel) {
        if ((value == null || value.isEmpty()) && emptyValueLabel != null) return emptyValueLabel;
        for (CustomRuntimeComponent entry : customEntries) {
            if (entry.getId().equals(value)) return entry.getDisplayName();
        }
        return value;
    }

    /** Entry point for the "+ Add custom Wine/Box64 build..." row: picks a local file via SAF,
     *  then collects a display name (and, for WINE, which translator family it belongs to --
     *  this cannot be sniffed from an arbitrary archive, see CustomRuntimeComponent) before
     *  staging it and enqueuing CustomComponentInstallForegroundService. */
    private void showAddCustomComponentFlow(CustomRuntimeComponent.Kind kind) {
        String[] mimeTypes = kind == CustomRuntimeComponent.Kind.WINE
            ? new String[] {"application/x-xz", "application/zstd", "application/gzip",
                "application/x-tar", "application/octet-stream"}
            : new String[] {"application/vnd.debian.binary-package", "application/octet-stream"};
        listener.onPickCustomComponentFile(mimeTypes, uri -> {
            if (uri == null) return;
            showCustomComponentMetadataDialog(kind, uri);
        });
    }

    private void showCustomComponentMetadataDialog(CustomRuntimeComponent.Kind kind, Uri uri) {
        LinearLayout form = new LinearLayout(getContext());
        form.setOrientation(LinearLayout.VERTICAL);
        int margin = dp(20);
        form.setPadding(margin, 0, margin, 0);
        EditText name = new EditText(getContext());
        name.setHint(R.string.local_games_custom_component_name_hint);
        name.setSingleLine(true);
        name.setTextColor(color(R.color.local_games_on_surface));
        name.setHintTextColor(color(R.color.local_games_on_surface_secondary));
        form.addView(name);
        RadioGroup translatorGroup = null;
        if (kind == CustomRuntimeComponent.Kind.WINE) {
            translatorGroup = new RadioGroup(getContext());
            translatorGroup.setOrientation(RadioGroup.VERTICAL);
            RadioButton hangover = new RadioButton(getContext());
            hangover.setId(View.generateViewId());
            hangover.setText(R.string.local_games_custom_component_translator_hangover);
            hangover.setTextColor(color(R.color.local_games_on_surface));
            RadioButton box64 = new RadioButton(getContext());
            box64.setId(View.generateViewId());
            box64.setText(R.string.local_games_custom_component_translator_box64);
            box64.setTextColor(color(R.color.local_games_on_surface));
            translatorGroup.addView(hangover);
            translatorGroup.addView(box64);
            translatorGroup.check(box64.getId());
            form.addView(translatorGroup);
        }
        RadioGroup finalTranslatorGroup = translatorGroup;
        new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(getContext().getString(kind == CustomRuntimeComponent.Kind.WINE
                ? R.string.local_games_custom_component_add_wine
                : R.string.local_games_custom_component_add_box64))
            .setView(form)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_games_custom_component_add_action, (dialog, which) -> {
                String displayName = name.getText().toString().trim();
                if (displayName.isEmpty()) {
                    Toast.makeText(getContext(),
                        R.string.local_games_custom_component_name_required, Toast.LENGTH_LONG).show();
                    return;
                }
                RuntimeTranslator translator = null;
                if (kind == CustomRuntimeComponent.Kind.WINE) {
                    RadioButton checked = finalTranslatorGroup.findViewById(
                        finalTranslatorGroup.getCheckedRadioButtonId());
                    translator = checked != null && checked.getText().toString().equals(
                        getContext().getString(R.string.local_games_custom_component_translator_hangover))
                        ? RuntimeTranslator.HANGOVER : RuntimeTranslator.BOX64;
                }
                startCustomComponentInstall(kind, translator, displayName, uri);
            })
            .show();
    }

    private void startCustomComponentInstall(CustomRuntimeComponent.Kind kind,
            @Nullable RuntimeTranslator translator, String displayName, Uri uri) {
        String slug = displayName.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "build";
        if (slug.length() > 64) slug = slug.substring(0, 64);
        String componentId = (kind == CustomRuntimeComponent.Kind.WINE ? "custom-wine-" : "custom-box64-")
            + slug;
        try {
            if (customComponents.find(componentId).isPresent()) {
                Toast.makeText(getContext(),
                    R.string.local_games_custom_component_id_exists, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (IOException ignored) {
            // Fall through -- a transient read failure here just means the id-collision check is
            // skipped; the install itself will still fail cleanly if the id truly collides, since
            // the registry save at the end is also a plain overwrite-by-id.
        }
        String finalComponentId = componentId;
        RuntimeTranslator finalTranslator = translator;
        Toast.makeText(getContext(), R.string.local_games_custom_component_installing,
            Toast.LENGTH_SHORT).show();
        ioExecutor.execute(() -> {
            File staged;
            String sha256;
            try {
                staged = stageCustomComponentPayload(uri);
                sha256 = sha256Of(staged);
            } catch (IOException | NoSuchAlgorithmException | RuntimeException error) {
                post(() -> Toast.makeText(getContext(),
                    R.string.local_games_custom_component_pick_failed, Toast.LENGTH_LONG).show());
                return;
            }
            String taskId = CustomComponentInstallTasks.enqueue(getContext(), finalComponentId, kind,
                finalTranslator, displayName, staged, sha256);
            post(() -> showCustomComponentConsole(taskId));
        });
    }

    private File stageCustomComponentPayload(Uri uri) throws IOException {
        File directory = paths.getCustomComponentStagingDirectory();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("custom_component_staging_directory_failed");
        }
        File staged = new File(directory, "pick-" + UUID.randomUUID() + ".payload");
        try (InputStream input = getContext().getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("custom_component_source_unavailable");
            try (OutputStream output = new FileOutputStream(staged)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) output.write(buffer, 0, read);
            }
        }
        return staged;
    }

    private static String sha256Of(File file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
        return hex.toString();
    }

    private void showCustomComponentConsole(String taskId) {
        RuntimeSetupConsoleDialog.TaskStatusLookup lookup = id -> {
            try {
                CustomComponentInstallTask task = new FileCustomComponentInstallTaskRepository(
                    paths.getCustomComponentInstallTasksDirectory()).find(id).orElse(null);
                if (task == null) return null;
                return new RuntimeSetupConsoleDialog.TaskStatusLookup.Status(
                    task.getState() == CustomComponentInstallTaskState.FAILED, false,
                    task.getState().name());
            } catch (IOException | RuntimeException ignored) {
                return null;
            }
        };
        RuntimeSetupConsoleDialog.show(getContext(), taskId,
                R.string.local_games_custom_component_console_title, lookup)
            .setOnDismissListener(dialog -> reloadCustomComponentsAndRender());
    }

    private void renderAdvanced() {
        pageTitle("Advanced");
        addChoice("Box64 preset", withCurrent(profile.getBox64Preset(), "STABILITY", "INTERMEDIATE", "PERFORMANCE"),
            value -> replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
                profile.getAudioDriver(), profile.getResolution(), value, profile.getEnvironment(),
                profile.getInputProfileId(), profile.getLaunchExecutionMode()));
        if (profile.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT) {
            // Orthogonal to the Wine package choice: which Box64 binary translates it, independent
            // of which Wine build is selected. "" means "no override" -- rootfs_prefix_warmup.sh
            // falls back to the apt-installed stock binary. See GAMES_CUSTOM_BOX64_ID wiring in
            // GameContainerProfileResolver.
            addChoiceWithAddCustom("Box64 build", option("GAMES_CUSTOM_BOX64_ID"), new String[] {""},
                customBox64Entries, getContext().getString(R.string.local_games_box64_build_default),
                getContext().getString(R.string.local_games_custom_component_add_box64),
                value -> setOption("GAMES_CUSTOM_BOX64_ID", value),
                () -> showAddCustomComponentFlow(CustomRuntimeComponent.Kind.BOX64));
        }
        addChoice("Startup option", labels("App shell", "Terminal session"),
            profile.getLaunchExecutionMode() == LaunchExecutionMode.APP_SHELL ? 0 : 1,
            which -> replace(profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
                profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(),
                profile.getEnvironment(), profile.getInputProfileId(), which == 0
                    ? LaunchExecutionMode.APP_SHELL : LaunchExecutionMode.TERMINAL_SESSION));
        addChoice("Windows version", labels("Windows 7", "Windows 10", "Windows 11"),
            choiceIndex(option("GAMES_WINDOWS_VERSION"), "Windows 7", "Windows 10", "Windows 11"),
            which -> setOption("GAMES_WINDOWS_VERSION",
                labels("Windows 7", "Windows 10", "Windows 11")[which]));
        addCpuCoreSelector("CPU cores", "GAMES_CPU_CORES");
        addCpuCoreSelector("CPU cores (32-bit applications)", "GAMES_CPU_CORES_32");
    }

    private void pageTitle(String value) {
        title.setText(value);
        if (header.getVisibility() != View.VISIBLE) {
            TextView heading = text(value, 20, R.color.local_games_on_surface);
            heading.setTypeface(Typeface.DEFAULT_BOLD);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            params.setMargins(dp(4), dp(4), dp(4), dp(14));
            content.addView(heading, params);
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
        String current = checked >= 0 && checked < values.length ? values[checked] : "Not selected";
        addRow(label, current, true, view -> new MaterialAlertDialogBuilder(getContext(),
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
        if ("Execution method".equals(label)) return profile.getLaunchExecutionMode() ==
            LaunchExecutionMode.APP_SHELL ? "App shell" : "Terminal session";
        if ("Game resolution".equals(label)) return profile.getResolution();
        if ("Graphics driver".equals(label)) return profile.getGraphicsDriver();
        if ("DirectX translation".equals(label)) return profile.getDxWrapper();
        if ("Wine package".equals(label)) return profile.getWinePackage();
        if ("Audio driver".equals(label)) return profile.getAudioDriver();
        if ("Box64 preset".equals(label)) return profile.getBox64Preset();
        return emptyAs(profile.getInputProfileId(), "default");
    }

    private void addEdit(String label, String value, boolean multiline, EditCallback callback) {
        addRow(label, value.isEmpty() ? "No overrides" : value.replace('\n', ' '), true, view -> {
            EditText input = new EditText(getContext());
            input.setText(value);
            input.setTextColor(color(R.color.local_games_on_surface));
            input.setHintTextColor(color(R.color.local_games_on_surface_secondary));
            input.setInputType(multiline ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : InputType.TYPE_CLASS_TEXT);
            input.setMinLines(multiline ? 6 : 1);
            new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
                .setTitle(label)
                .setView(input)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Apply", (dialog, which) -> {
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
        addRow(label, current, true, view -> new MaterialAlertDialogBuilder(getContext(),
            R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(label)
            .setMultiChoiceItems(cores, selected, (dialog, which, checked) -> selected[which] = checked)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Apply", (dialog, which) -> {
                setOption(key, selectedCoreList(selected));
                render();
            })
            .show());
    }

    private void addDllChoice(String label, String key) {
        String[] values = labels("Built-in", "Native", "Native, built-in");
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

    private static String selectedCoreSummary(boolean[] selected) {
        String value = selectedCoreList(selected);
        if (value.isEmpty()) return "No cores selected";
        for (boolean core : selected) if (!core) return value;
        return "All available";
    }

    private void addRow(String label, String summary, boolean interactive, @Nullable View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(13), dp(14), dp(13));
        row.setBackground(rowBackground());
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dp(8);
        content.addView(row, rowParams);
        LinearLayout copy = new LinearLayout(getContext());
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView labelView = text(label, 17, R.color.local_games_on_surface);
        copy.addView(labelView);
        if (summary != null && !summary.isEmpty()) {
            TextView summaryView = text(summary, 13, R.color.local_games_on_surface_secondary);
            summaryView.setMaxLines(2);
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            summaryParams.topMargin = dp(3);
            copy.addView(summaryView, summaryParams);
        }
        row.addView(copy, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (interactive) {
            TextView arrow = text("›", 30, R.color.local_games_primary);
            arrow.setGravity(Gravity.CENTER);
            row.addView(arrow, new LinearLayout.LayoutParams(dp(28), dp(36)));
            row.setClickable(true);
            row.setOnClickListener(listener);
        }
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
            new MaterialAlertDialogBuilder(getContext(), R.style.ThemeOverlay_TermuxLocalGames_Dialog)
                .setTitle("Save runtime parameters?")
                .setMessage("Changes apply only to " + game.getName() + ".")
                .setNegativeButton("Discard", (dialog, which) -> close())
                .setNeutralButton(android.R.string.cancel, null)
                .setPositiveButton("Save", (dialog, which) -> saveAndFinish())
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
        RuntimeProfile toSave = profile;
        Game gameToSave = game;
        boolean saveGame = gameChanged();
        boolean rootfs = toSave.getRuntimeBackendType() == GameRuntimeBackendType.ROOTFS_PROOT;
        android.util.Log.d("GameRuntimeOptionsDebug", "saveAndFinish toSave.backend="
            + toSave.getRuntimeBackendType() + " containerId=" + toSave.getContainerId());
        ioExecutor.execute(() -> {
            try {
                profileRepository.save(toSave);
                android.util.Log.d("GameRuntimeOptionsDebug", "profileRepository.save completed");
                String warmupTaskId = RuntimeWarmup.warm(getContext(), toSave);
                if (saveGame) gameRepository.save(gameToSave);
                post(() -> {
                    if (!destroyed) listener.onRuntimeOptionsSaved(warmupTaskId, rootfs);
                });
            } catch (IOException | RuntimeException error) {
                android.util.Log.e("GameRuntimeOptionsDebug", "save failed", error);
                post(() -> Toast.makeText(getContext(), "Unable to save runtime parameters",
                    Toast.LENGTH_LONG).show());
            }
        });
    }

    public void release() {
        destroyed = true;
        ioExecutor.shutdownNow();
    }

    /** Embedded landscape panels use the device back key instead of a second navigation bar. */
    public void setShowHeader(boolean show) {
        header.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void close() {
        if (listener != null) listener.onRuntimeOptionsClosed();
    }

    private TextView text(String value, int sizeSp, int colorRes) {
        TextView view = new TextView(getContext());
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color(colorRes));
        return view;
    }

    private android.graphics.drawable.GradientDrawable rowBackground() {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(color(R.color.local_games_surface));
        shape.setCornerRadius(dp(18));
        shape.setStroke(dp(1), color(R.color.local_games_outline));
        return shape;
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
