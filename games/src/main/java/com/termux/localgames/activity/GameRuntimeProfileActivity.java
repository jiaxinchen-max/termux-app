package com.termux.localgames.activity;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.termux.localgames.R;
import com.termux.localgames.api.LegacyRuntimeConfiguration;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.LocalGamesHost;
import com.termux.localgames.api.PrefixProvisionTasks;
import com.termux.localgames.components.index.ComponentDescriptor;
import com.termux.localgames.components.index.ComponentIndex;
import com.termux.localgames.components.index.ComponentIndexParser;
import com.termux.localgames.components.index.ComponentType;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileGameContainerRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.RuntimeProfileRepository;
import com.termux.localgames.databinding.ActivityGameRuntimeProfileBinding;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.GameRuntimeBackendType;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.RuntimeEnvironment;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfileChange;
import com.termux.localgames.domain.RuntimeProfileDiff;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.domain.RuntimeTranslator;
import com.termux.localgames.runtime.LaunchPreflightResult;
import com.termux.localgames.runtime.LegacyRuntimeProfileMapper;
import com.termux.localgames.runtime.GameContainerFactory;
import com.termux.localgames.runtime.GameContainerProfileResolver;
import com.termux.localgames.runtime.PreflightIssue;
import com.termux.localgames.service.AndroidLaunchPreflight;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Game-owned RuntimeProfile editor and read-only launch preflight. */
public final class GameRuntimeProfileActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_ID = "com.termux.localgames.extra.RUNTIME_GAME_ID";
    private static final String INDEX_ASSET = "termux-box-packages/index-v1.json";
    private static final String STATE_PREFIX = "runtime_profile.";
    private static final String STATE_CATEGORY = STATE_PREFIX + "category";
    private static final String STATE_CONTAINER = STATE_PREFIX + "container";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesRuntimeProfileIo"));
    private final LegacyRuntimeProfileMapper legacyMapper = new LegacyRuntimeProfileMapper();

    private ActivityGameRuntimeProfileBinding binding;
    private RuntimeProfileRepository profileRepository;
    private FileGameRepository gameRepository;
    private FileGameContainerRepository containerRepository;
    private String gameId;
    private Game game;
    private RuntimeProfile baseline;
    private RuntimeProfile lastSuccessful;
    private List<GameContainer> availableContainers = Collections.emptyList();
    private String boundContainerId = GameContainer.DEFAULT_ID;
    private List<LegacyRuntimeConfiguration> legacyConfigurations = Collections.emptyList();
    private ComponentIndex componentIndex;
    private List<ComponentSelection.Choice> wineChoices = Collections.emptyList();
    private List<ComponentSelection.Choice> graphicsChoices = Collections.emptyList();
    private List<ComponentSelection.Choice> dxChoices = Collections.emptyList();
    private Map<String, String> formComponentVersions = Collections.emptyMap();
    private Bundle restoredState;
    private int operationGeneration;
    private boolean destroyed;
    private int selectedCategory;
    private final List<View> gameHubPanels = new ArrayList<>();
    private final List<TextView> gameHubCategoryButtons = new ArrayList<>();
    private SharedPreferences gameHubPreferences;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGameRuntimeProfileBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        gameId = getIntent().getStringExtra(EXTRA_GAME_ID);
        gameHubPreferences = getSharedPreferences("game_engine_settings", MODE_PRIVATE);
        restoredState = savedInstanceState;
        GameStoragePaths gamePaths = new GameStoragePaths(getFilesDir());
        gameRepository = new FileGameRepository(gamePaths.getLibraryDirectory());
        profileRepository = new FileRuntimeProfileRepository(gamePaths.getProfilesDirectory());
        containerRepository = new FileGameContainerRepository(gamePaths.getContainersDirectory());

        binding.runtimeProfileToolbar.setNavigationOnClickListener(view -> finish());
        GamesHelpDialog.attach(binding.runtimeProfileToolbar,
            R.string.local_game_runtime_profile_title,
            R.string.local_game_runtime_profile_help);
        binding.runtimeProfilePresetRecommended.setOnClickListener(view ->
            applyPreset(RuntimeProfilePreset.RECOMMENDED));
        binding.runtimeProfilePresetStable.setOnClickListener(view ->
            applyPreset(RuntimeProfilePreset.STABLE));
        binding.runtimeProfilePresetCompatibility.setOnClickListener(view ->
            applyPreset(RuntimeProfilePreset.COMPATIBILITY));
        binding.runtimeProfilePresetCustom.setOnClickListener(view -> refreshDerivedState());
        binding.runtimeProfileApplyLegacy.setOnClickListener(view -> applySelectedLegacy());
        binding.runtimeProfileCheck.setOnClickListener(view -> runPreflightFromForm());
        binding.runtimeProfileSave.setOnClickListener(view -> saveProfile());
        binding.runtimeProfileRestoreDefault.setOnClickListener(view ->
            applyPreset(RuntimeProfilePreset.RECOMMENDED));
        binding.runtimeProfileRestoreSuccess.setOnClickListener(view -> {
            if (lastSuccessful != null) applyProfile(lastSuccessful);
        });
        binding.runtimeProfileExecutionMode.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_dropdown_item_1line, executionModeLabels()));
        binding.runtimeProfileBackend.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_dropdown_item_1line, runtimeBackendLabels()));
        binding.runtimeProfileExecutionMode.setOnItemClickListener((parent, view, position, id) ->
            refreshDerivedState());
        binding.runtimeProfileBackend.setOnItemClickListener((parent, view, position, id) -> {
            GameRuntimeBackendType backend = runtimeBackendFromForm();
            configureComponentChoices(backend);
            applyBackendDefaults(backend);
            refreshDerivedState();
        });
        binding.runtimeProfileWine.setOnItemClickListener((parent, view, position, id) ->
            refreshDerivedState());
        binding.runtimeProfileGraphics.setOnItemClickListener((parent, view, position, id) ->
            refreshDerivedState());
        binding.runtimeProfileDx.setOnItemClickListener((parent, view, position, id) ->
            refreshDerivedState());
        TextWatcher changesWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start,
                                                    int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start,
                                                int before, int count) { }
            @Override public void afterTextChanged(Editable value) { refreshChangesFromForm(); }
        };
        TextView[] watchedFields = {
            binding.runtimeProfileRootfs, binding.runtimeProfileWine,
            binding.runtimeProfileGraphics, binding.runtimeProfileDx,
            binding.runtimeProfileAudio, binding.runtimeProfileResolution,
            binding.runtimeProfileBox64, binding.runtimeProfileInput,
            binding.runtimeProfileEnvironment
        };
        for (TextView field : watchedFields) field.addTextChangedListener(changesWatcher);
        selectedCategory = savedInstanceState == null ? 0 :
            savedInstanceState.getInt(STATE_CATEGORY, 0);
        initializeCategoryNavigation();

        if (TextUtils.isEmpty(gameId)) {
            Toast.makeText(this, R.string.local_game_detail_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        load();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putInt(STATE_CATEGORY, selectedCategory);
        outState.putString(STATE_CONTAINER, boundContainerId);
        if (baseline != null && binding != null) {
            outState.putString(STATE_PREFIX + "runtimeBackend",
                runtimeBackendFromForm().getStorageValue());
            outState.putString(STATE_PREFIX + "rootfs", text(binding.runtimeProfileRootfs));
            outState.putString(STATE_PREFIX + "wine", text(binding.runtimeProfileWine));
            outState.putString(STATE_PREFIX + "graphics", text(binding.runtimeProfileGraphics));
            outState.putString(STATE_PREFIX + "dx", text(binding.runtimeProfileDx));
            outState.putString(STATE_PREFIX + "audio", text(binding.runtimeProfileAudio));
            outState.putString(STATE_PREFIX + "resolution", text(binding.runtimeProfileResolution));
            outState.putString(STATE_PREFIX + "box64", text(binding.runtimeProfileBox64));
            outState.putString(STATE_PREFIX + "input", text(binding.runtimeProfileInput));
            outState.putString(STATE_PREFIX + "executionMode",
                executionModeFromForm().getStorageValue());
            outState.putString(STATE_PREFIX + "environment", text(binding.runtimeProfileEnvironment));
            outState.putStringArrayList(STATE_PREFIX + "componentKeys",
                new ArrayList<>(formComponentVersions.keySet()));
            outState.putStringArrayList(STATE_PREFIX + "componentValues",
                new ArrayList<>(formComponentVersions.values()));
        }
        super.onSaveInstanceState(outState);
    }

    private void initializeCategoryNavigation() {
        int[] categoryIds = {
            R.id.runtime_profile_category_input,
            R.id.runtime_profile_category_general,
            R.id.runtime_profile_category_container,
            R.id.runtime_profile_category_graphics,
            R.id.runtime_profile_category_compatibility,
            R.id.runtime_profile_category_backup,
            R.id.runtime_profile_category_components,
            R.id.runtime_profile_category_developer
        };
        for (int index = 0; index < categoryIds.length; index++) {
            View view = findViewById(categoryIds[index]);
            if (!(view instanceof TextView)) {
                gameHubCategoryButtons.clear();
                return;
            }
            final int category = index;
            view.setOnClickListener(clicked -> showCategory(category));
            gameHubCategoryButtons.add((TextView) view);
        }
        showCategory(selectedCategory);
    }

    private void showCategory(int category) {
        if (!gameHubPanels.isEmpty()) {
            selectedCategory = Math.max(0, Math.min(gameHubPanels.size() - 1, category));
            int selectedColor = ContextCompat.getColor(this, R.color.local_games_on_surface);
            int normalColor = ContextCompat.getColor(this,
                R.color.local_games_on_surface_secondary);
            for (int index = 0; index < gameHubPanels.size(); index++) {
                gameHubPanels.get(index).setVisibility(index == selectedCategory
                    ? View.VISIBLE : View.GONE);
            }
            for (int index = 0; index < gameHubCategoryButtons.size(); index++) {
                boolean selected = index == selectedCategory;
                TextView button = gameHubCategoryButtons.get(index);
                button.setTextColor(selected ? selectedColor : normalColor);
                button.setActivated(selected);
            }
            return;
        }
        View[] groups = {
            findViewById(R.id.runtime_profile_group_general),
            findViewById(R.id.runtime_profile_group_graphics),
            findViewById(R.id.runtime_profile_group_compatibility),
            findViewById(R.id.runtime_profile_group_input),
            findViewById(R.id.runtime_profile_group_diagnostics)
        };
        if (groups[0] == null) return;
        TextView[] buttons = new TextView[0];
        if (!gameHubCategoryButtons.isEmpty()) {
            buttons = gameHubCategoryButtons.toArray(new TextView[0]);
        }
        if (buttons.length == 0) return;
        selectedCategory = Math.max(0, Math.min(buttons.length - 1, category));
        int selectedColor = ContextCompat.getColor(this, R.color.local_games_on_surface);
        int normalColor = ContextCompat.getColor(this,
            R.color.local_games_on_surface_secondary);
        for (int index = 0; index < groups.length; index++) {
            groups[index].setVisibility(View.GONE);
        }
        groups[Math.min(selectedCategory, groups.length - 1)].setVisibility(View.VISIBLE);
        for (int index = 0; index < buttons.length; index++) {
            boolean selected = index == selectedCategory;
            buttons[index].setTextColor(selected ? selectedColor : normalColor);
            buttons[index].setActivated(selected);
        }
    }

    private void load() {
        int generation = ++operationGeneration;
        binding.runtimeProfileProgress.show();
        binding.runtimeProfileContent.setVisibility(View.GONE);
        ioExecutor.execute(() -> {
            Game loadedGame = null;
            RuntimeProfile loadedProfile = null;
            RuntimeProfile loadedSuccess = null;
            ComponentIndex loadedComponentIndex = null;
            List<LegacyRuntimeConfiguration> loadedLegacy = Collections.emptyList();
            List<GameContainer> loadedContainers = Collections.emptyList();
            String error = null;
            try {
                Optional<Game> found = gameRepository.find(gameId);
                if (!found.isPresent()) throw new IOException("game_not_found");
                loadedGame = found.get();
                loadedProfile = profileRepository.find(gameId).orElseGet(() ->
                    RuntimeProfilePresets.create(gameId, RuntimeProfilePreset.RECOMMENDED));
                loadedSuccess = profileRepository.findLastSuccessful(gameId).orElse(null);
                loadedContainers = new ArrayList<>(containerRepository.list());
                if (GameContainer.DEFAULT_ID.equals(loadedProfile.getContainerId())) {
                    GameContainer defaultContainer = null;
                    for (GameContainer candidate : loadedContainers) {
                        if (GameContainer.DEFAULT_ID.equals(candidate.getId())) {
                            defaultContainer = candidate;
                            break;
                        }
                    }
                    // The default id is the only shared runtime and is always GLIBC.  Older
                    // installs could have stored an arbitrary backend under this id.
                    if (defaultContainer == null || defaultContainer.getBackendType() !=
                        GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
                        defaultContainer = GameContainerFactory.globalGlibcFromProfile(loadedProfile);
                        containerRepository.save(defaultContainer);
                        loadedContainers.removeIf(container -> GameContainer.DEFAULT_ID.equals(
                            container.getId()));
                        loadedContainers.add(defaultContainer);
                    }
                    loadedProfile = new GameContainerProfileResolver().resolve(loadedProfile,
                        defaultContainer);
                    profileRepository.save(loadedProfile);
                }
                try (InputStream input = getAssets().open(INDEX_ASSET)) {
                    loadedComponentIndex = new ComponentIndexParser().parse(input);
                }
                try {
                    loadedLegacy = LocalGames.requireHost(this)
                        .listLegacyRuntimeConfigurations();
                } catch (RuntimeException ignored) {
                    loadedLegacy = Collections.emptyList();
                }
            } catch (IOException | RuntimeException loadError) {
                error = safeMessage(loadError);
            }
            Game loadedGameResult = loadedGame;
            RuntimeProfile loadedProfileResult = loadedProfile;
            RuntimeProfile loadedSuccessResult = loadedSuccess;
            ComponentIndex loadedComponentIndexResult = loadedComponentIndex;
            List<LegacyRuntimeConfiguration> loadedLegacyResult = loadedLegacy;
            List<GameContainer> loadedContainersResult = loadedContainers;
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                binding.runtimeProfileProgress.hide();
                if (failure != null) {
                    Toast.makeText(this, getString(
                        R.string.local_game_runtime_profile_load_failed, failure),
                        Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                game = loadedGameResult;
                baseline = loadedProfileResult;
                lastSuccessful = loadedSuccessResult;
                componentIndex = loadedComponentIndexResult;
                legacyConfigurations = loadedLegacyResult;
                availableContainers = loadedContainersResult;
                boundContainerId = baseline.getContainerId();
                renderLoadedState();
            });
        });
    }

    private void renderLoadedState() {
        binding.runtimeProfileContent.setVisibility(View.VISIBLE);
        binding.runtimeProfileGameName.setText(game.getName());
        binding.runtimeProfileRestoreSuccess.setEnabled(lastSuccessful != null);
        List<String> labels = new ArrayList<>();
        for (LegacyRuntimeConfiguration legacy : legacyConfigurations) {
            labels.add(legacy.getName() + " (" + legacy.getId() + ")");
        }
        binding.runtimeProfileLegacy.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_dropdown_item_1line, labels));
        binding.runtimeProfileLegacyLayout.setVisibility(
            labels.isEmpty() ? View.GONE : View.VISIBLE);
        binding.runtimeProfileApplyLegacy.setVisibility(
            labels.isEmpty() ? View.GONE : View.VISIBLE);
        configureComponentChoices(baseline.getRuntimeBackendType());
        applyProfile(baseline);
        if (restoredState != null) {
            restoreForm(restoredState);
            restoredState = null;
            refreshDerivedState();
        }
        installGameHubPanels();
    }

    /** Builds the GameHub-style PC engine shell while retaining the existing profile model. */
    private void installGameHubPanels() {
        if (gameHubCategoryButtons.size() != 8 || !gameHubPanels.isEmpty()) return;
        View[] legacyGroups = {
            binding.runtimeProfileGroupGeneral, binding.runtimeProfileGroupGraphics,
            binding.runtimeProfileGroupCompatibility, binding.runtimeProfileGroupInput,
            binding.runtimeProfileGroupDiagnostics
        };
        for (View group : legacyGroups) group.setVisibility(View.GONE);

        LinearLayout host = binding.runtimeProfileContent;
        gameHubPanels.add(buildInputPanel());
        gameHubPanels.add(buildGeneralPanel());
        gameHubPanels.add(buildContainerPanel());
        gameHubPanels.add(buildGraphicsPanel());
        gameHubPanels.add(buildCompatibilityPanel());
        gameHubPanels.add(buildBackupPanel());
        gameHubPanels.add(buildComponentsPanel());
        gameHubPanels.add(buildDeveloperPanel());
        for (View panel : gameHubPanels) host.addView(panel, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        showCategory(selectedCategory);
    }

    private LinearLayout buildInputPanel() {
        LinearLayout panel = newSettingsPanel();
        addSwitchRow(panel, "input.controllerCompatibility", "Controller compatibility mode",
            "Enable controller mapping compatibility", true);
        addSwitchRow(panel, "input.virtualButtons", "Enable virtual buttons",
            "Show the in-game floating controls", false);
        addActionRow(panel, "Keys & layout", "Choose, edit and import control layouts", () ->
            showInfo("Keys & layout", "Leaderboard\nCustom\nMy layouts\n\nLayout editor and import/export are available from this entry."));
        addActionRow(panel, "Input device list", "Virtual controller", () ->
            showInfo("Input device list", "Virtual controller\nEnabled\nInput mapping"));
        return panel;
    }

    private LinearLayout buildGeneralPanel() {
        LinearLayout panel = newSettingsPanel();
        addActionRow(panel, "Start file path", game == null ? "" : game.getExecutable(), () ->
            showInfo("Start file path", game == null ? "" : game.getExecutable()));
        addEditableRow(panel, "general.language", "Game language", "zh_CN", "Game language", null);
        addEnvironmentVariablesRow(panel);
        addEditableRow(panel, "general.arguments", "Launch parameters",
            game == null ? "" : joinArguments(game), "Launch parameters", null);
        addSwitchRow(panel, "general.softKeyboard", "Auto-show soft keyboard",
            "Show when an input field is focused", true);
        return panel;
    }

    private LinearLayout buildContainerPanel() {
        LinearLayout panel = newSettingsPanel();
        GameContainer container = boundContainer();
        boolean globalGlibc = GameContainer.DEFAULT_ID.equals(boundContainerId);
        addActionRow(panel, "Container mode", globalGlibc
            ? "Global GLIBC" : "Independent container", this::showContainerModePicker);
        if (globalGlibc) {
            addActionRow(panel, "Global runtime", "Shared GLIBC prefix for all games", () ->
                showInfo("Global GLIBC", "This game uses the global GLIBC runtime. " +
                    "Create an independent container to isolate its Wine prefix and runtime settings."));
        } else {
            addActionRow(panel, "Independent container", container == null ? boundContainerId :
                container.getName() + " · " + container.getId(), () ->
                showInfo("Independent container", "This container is bound only to this game."));
            addChoiceRow(panel, "container.translator", "Runtime translator",
                container == null ? "Box64" : translatorLabel(container.getTranslator()),
                new String[] { "Hangover", "Box64", "FEX" }, value ->
                    updateBoundContainerTranslator(translatorFromLabel(value)));
            addChoiceRow(panel, "container.backend", "Runtime backend",
                container == null ? runtimeBackendLabel(runtimeBackendFromForm()) :
                    runtimeBackendLabel(container.getBackendType()),
                new String[] { runtimeBackendLabel(GameRuntimeBackendType.GLIBC_TERMUX_BOX),
                    runtimeBackendLabel(GameRuntimeBackendType.ROOTFS_PROOT) }, value ->
                    updateBoundContainerBackend(value));
        }
        addActionRow(panel, "Enter virtual desktop", "Open the bound container's Wine desktop",
            () -> showInfo("Virtual desktop", "The selected container will be used for this Wine desktop."));
        addActionRow(panel, "Run program…", "EXE · MSI · BAT · REG", () ->
            showInfo("Run program", "Choose an .exe, .msi, .bat or .reg file from the game folder."));
        return panel;
    }

    @Nullable
    private GameContainer boundContainer() {
        for (GameContainer container : availableContainers) {
            if (boundContainerId.equals(container.getId())) return container;
        }
        return null;
    }

    private void showContainerModePicker() {
        boolean globalGlibc = GameContainer.DEFAULT_ID.equals(boundContainerId);
        new AlertDialog.Builder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle("Container mode")
            .setSingleChoiceItems(new String[] { "Global GLIBC", "Independent container" },
                globalGlibc ? 0 : 1, (dialog, which) -> {
                if (which == 0 && !globalGlibc) useGlobalGlibc();
                if (which == 1 && globalGlibc) showCreateContainer();
                dialog.dismiss();
            })
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    private void useGlobalGlibc() {
        if (baseline == null) return;
        saveContainer(GameContainerFactory.globalGlibcFromProfile(baseline), true);
    }

    private void applyBoundContainer(GameContainer container) {
        boundContainerId = container.getId();
        RuntimeProfile source = baseline.withContainerId(container.getId());
        RuntimeProfile resolved = new GameContainerProfileResolver().resolve(source, container);
        baseline = resolved;
        configureComponentChoices(resolved.getRuntimeBackendType());
        applyProfile(resolved);
        refreshDerivedState();
        Toast.makeText(this, "Bound to " + container.getName(), Toast.LENGTH_SHORT).show();
    }

    private void showCreateContainer() {
        EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setHint("Container name");
        name.setText(game == null ? "Game container" : game.getName() + " container");
        new AlertDialog.Builder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle("Create container")
            .setView(name, dp(22), 0, dp(22), 0)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Create", (dialog, which) -> {
                String displayName = name.getText().toString().trim();
                if (displayName.isEmpty()) return;
                GameContainer source = boundContainer();
                if (source == null) source = GameContainerFactory.fromProfile(baseline);
                String id = "container-" + UUID.randomUUID().toString().substring(0, 8);
                GameContainer created = new GameContainer(id, displayName, source.getBackendType(),
                    source.getRootfsPackage(), source.getTranslator(), source.getWinePackage(),
                    source.getGraphicsDriver(), source.getDxWrapper(), source.getAudioDriver(),
                    source.getResolution(), source.getBox64Preset(), source.getEnvironment());
                saveContainer(created, true);
            })
            .show();
    }

    private void updateBoundContainerTranslator(RuntimeTranslator translator) {
        GameContainer source = boundContainer();
        if (source == null) return;
        saveContainer(new GameContainer(source.getId(), source.getName(), source.getBackendType(),
            source.getRootfsPackage(), translator, source.getWinePackage(), source.getGraphicsDriver(),
            source.getDxWrapper(), source.getAudioDriver(), source.getResolution(),
            source.getBox64Preset(), source.getEnvironment()), false);
    }

    private void updateBoundContainerBackend(String label) {
        GameContainer source = boundContainer();
        if (source == null) return;
        GameRuntimeBackendType backend = runtimeBackendLabel(GameRuntimeBackendType.ROOTFS_PROOT)
            .equals(label) ? GameRuntimeBackendType.ROOTFS_PROOT : GameRuntimeBackendType.GLIBC_TERMUX_BOX;
        String rootfs = backend == GameRuntimeBackendType.ROOTFS_PROOT
            ? GameContainer.ROOTFS_RUNTIME_PACKAGE
            : "";
        RuntimeProfile defaults = RuntimeProfilePresets.create(gameId,
            RuntimeProfilePreset.RECOMMENDED);
        String wine = backend == GameRuntimeBackendType.ROOTFS_PROOT ? "hangover-11.9"
            : defaults.getWinePackage();
        String graphics = backend == GameRuntimeBackendType.ROOTFS_PROOT ? "rootfs-llvmpipe"
            : defaults.getGraphicsDriver();
        String dx = backend == GameRuntimeBackendType.ROOTFS_PROOT ? "rootfs-wined3d"
            : defaults.getDxWrapper();
        String audio = backend == GameRuntimeBackendType.ROOTFS_PROOT ? "pulseaudio"
            : defaults.getAudioDriver();
        RuntimeTranslator translator = backend == GameRuntimeBackendType.ROOTFS_PROOT
            ? RuntimeTranslator.HANGOVER : RuntimeTranslator.BOX64;
        saveContainer(new GameContainer(source.getId(), source.getName(), backend, rootfs,
            translator, wine, graphics, dx, audio, source.getResolution(),
            source.getBox64Preset(), source.getEnvironment()), false);
    }

    private void saveContainer(GameContainer container, boolean bindAfterSave) {
        RuntimeProfile bindingProfile = baseline == null ? null :
            new GameContainerProfileResolver().resolve(baseline.withContainerId(container.getId()),
                container);
        boolean persistsBinding = bindAfterSave || container.getId().equals(boundContainerId);
        ioExecutor.execute(() -> {
            String error = null;
            try {
                containerRepository.save(container);
                if (persistsBinding && bindingProfile != null) {
                    // Do not leave a just-edited active container behind an old game binding.
                    profileRepository.save(bindingProfile);
                }
            }
            catch (IOException | RuntimeException saveError) { error = safeMessage(saveError); }
            String failure = error;
            runOnUiThread(() -> {
                if (destroyed) return;
                if (failure != null) {
                    showError(failure);
                    return;
                }
                List<GameContainer> updated = new ArrayList<>(availableContainers);
                boolean replaced = false;
                for (int index = 0; index < updated.size(); index++) {
                    if (updated.get(index).getId().equals(container.getId())) {
                        updated.set(index, container);
                        replaced = true;
                        break;
                    }
                }
                if (!replaced) updated.add(container);
                availableContainers = Collections.unmodifiableList(updated);
                if (bindAfterSave || container.getId().equals(boundContainerId)) {
                    applyBoundContainer(container);
                }
            });
        });
    }

    private static String translatorLabel(RuntimeTranslator translator) {
        switch (translator) {
            case HANGOVER: return "Hangover";
            case FEX: return "FEX";
            default: return "Box64";
        }
    }

    private static RuntimeTranslator translatorFromLabel(String value) {
        if ("Hangover".equals(value)) return RuntimeTranslator.HANGOVER;
        if ("FEX".equals(value)) return RuntimeTranslator.FEX;
        return RuntimeTranslator.BOX64;
    }

    private LinearLayout buildGraphicsPanel() {
        LinearLayout panel = newSettingsPanel();
        addEditableRow(panel, "graphics.resolution", "Game resolution", text(binding.runtimeProfileResolution),
            "Game resolution", value -> binding.runtimeProfileResolution.setText(value));
        addChoiceRow(panel, "graphics.gpuSpoof", "GPU model spoof", "Off",
            new String[] { "Off", "Adreno 740", "Adreno 830" }, null);
        addSwitchRow(panel, "graphics.mangohud", "MangoHUD overlay", "Show performance overlay", false);
        addChoiceRow(panel, "graphics.directxPanel", "DirectX performance panel", "Off",
            new String[] { "Off", "Compact", "Full" }, null);
        addSwitchRow(panel, "graphics.disableWm", "Disable window manager",
            "Use Wine desktop windowing only", false);
        addSwitchRow(panel, "graphics.disableFixes", "Disable graphics repair plugin",
            "Disable graphics compatibility fixes", false);
        addChoiceRow(panel, "graphics.driver", "GPU driver", text(binding.runtimeProfileGraphics), labels(graphicsChoices), value -> {
            setProfileSelection(binding.runtimeProfileGraphics, graphicsChoices,
                ComponentSelection.valueFor(graphicsChoices, value));
            refreshDerivedState();
        });
        addChoiceRow(panel, "graphics.dxvk", "DXVK version", text(binding.runtimeProfileDx), labels(dxChoices), value -> {
            setProfileSelection(binding.runtimeProfileDx, dxChoices,
                ComponentSelection.valueFor(dxChoices, value));
            refreshDerivedState();
        });
        addChoiceRow(panel, "graphics.vkd3d", "VKD3D version", "v2.14",
            new String[] { "v2.14", "v2.13", "Off" }, null);
        addChoiceRow(panel, "graphics.memory", "Memory limit", "4096 MB",
            new String[] { "512 MB", "1024 MB", "2048 MB", "4096 MB", "8192 MB" }, null);
        return panel;
    }

    private LinearLayout buildCompatibilityPanel() {
        LinearLayout panel = newSettingsPanel();
        addActionRow(panel, "Update latest cloud config", "Download compatibility recommendations", () ->
            Toast.makeText(this, "Cloud configuration update queued", Toast.LENGTH_SHORT).show());
        addChoiceRow(panel, "compatibility.wine", "Compatibility layer", text(binding.runtimeProfileWine), labels(wineChoices), value -> {
            setProfileSelection(binding.runtimeProfileWine, wineChoices,
                ComponentSelection.valueFor(wineChoices, value));
            refreshDerivedState();
        });
        addActionRow(panel, "Translation parameters", "FEX", this::showTranslationParameters);
        addChoiceRow(panel, "compatibility.dinput", "Dinput function library", "Prefer built-in",
            new String[] { "Prefer built-in", "Prefer native" }, null);
        addSwitchRow(panel, "compatibility.skipMedia", "Skip audio-video decoding",
            "Disable Media Foundation decoding", false);
        addEditableRow(panel, "compatibility.audio", "Audio driver", text(binding.runtimeProfileAudio),
            "Audio driver", value -> binding.runtimeProfileAudio.setText(value));
        addChoiceRow(panel, "compatibility.translator", "CPU translator", "FEX 20250910",
            new String[] { "FEX 20250910", "Box64", "System" }, null);
        addChoiceRow(panel, "compatibility.cpuCores", "CPU core limit", "Unlimited",
            new String[] { "Unlimited", "1", "2", "4", "8" }, null);
        return panel;
    }

    private LinearLayout buildBackupPanel() {
        LinearLayout panel = newSettingsPanel();
        addActionRow(panel, "Back up to local", "Back up this game's saves", () ->
            showInfo("Back up to local", "Game save backup will be created in the selected storage folder."));
        addActionRow(panel, "Restore from local backup", "Restore this game's saves", () ->
            showInfo("Restore from local backup", "Choose a game-save archive to restore."));
        addActionRow(panel, "Runtime image backup", "Export or restore GLIBC / PRootFS", () ->
            startActivity(LocalGames.createRuntimeBackupIntent(this)));
        return panel;
    }

    private LinearLayout buildComponentsPanel() {
        LinearLayout panel = newSettingsPanel();
        addActionRow(panel, "Installed components", "Wine · DXVK · drivers · fonts", () ->
            startActivity(LocalGames.createComponentsIntent(this)));
        addActionRow(panel, "Install new component", "Browse runtime component catalog", () ->
            startActivity(LocalGames.createComponentsIntent(this)));
        return panel;
    }

    private LinearLayout buildDeveloperPanel() {
        LinearLayout panel = newSettingsPanel();
        addSwitchRow(panel, "developer.logServer", "Enable log server",
            "Forward runtime logs for debugging", false);
        addEditableRow(panel, "developer.wineDebug", "Wine debug arguments", "fixme-all,+seh",
            "Wine debug arguments", null);
        addActionRow(panel, "Runtime logs", "Open the latest structured launch log", () ->
            showInfo("Runtime logs", "The latest launch log is available from the game task panel."));
        return panel;
    }

    private LinearLayout newSettingsPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(8), dp(14), dp(8));
        panel.setBackground(rounded(0xE61A2028, 20, 0x3AFFFFFF));
        panel.setVisibility(View.GONE);
        return panel;
    }

    private void addActionRow(LinearLayout panel, String title, String summary, Runnable action) {
        LinearLayout row = newSettingRow(panel, title, summary, true);
        row.setOnClickListener(view -> action.run());
    }

    private void addSwitchRow(LinearLayout panel, String key, String title, String summary,
                              boolean defaultValue) {
        LinearLayout row = newSettingRow(panel, title, summary, false);
        SwitchCompat toggle = new SwitchCompat(this);
        toggle.setChecked(gameHubPreferences.getBoolean(preferenceKey(key), defaultValue));
        toggle.setOnCheckedChangeListener((button, checked) -> gameHubPreferences.edit()
            .putBoolean(preferenceKey(key), checked).apply());
        row.addView(toggle, new LinearLayout.LayoutParams(dp(42), dp(36)));
        row.setOnClickListener(view -> toggle.setChecked(!toggle.isChecked()));
    }

    private void addChoiceRow(LinearLayout panel, String key, String title, String selected, String[] choices,
                              @Nullable ChoiceListener listener) {
        String stored = gameHubPreferences.getString(preferenceKey(key), selected);
        TextView detail = addActionRowWithDetail(panel, title, stored);
        View row = (View) detail.getTag();
        row.setOnClickListener(view -> showChoice(title, choices, detail, value -> {
            gameHubPreferences.edit().putString(preferenceKey(key), value).apply();
            if (listener != null) listener.onSelected(value);
        }));
    }

    private void addEditableRow(LinearLayout panel, String key, String title, String current,
                                String dialogTitle, @Nullable ChoiceListener listener) {
        String stored = gameHubPreferences.getString(preferenceKey(key), current);
        TextView detail = addActionRowWithDetail(panel, title, emptyToDash(stored));
        View row = (View) detail.getTag();
        row.setOnClickListener(view -> showEditor(dialogTitle, detail, value -> {
            gameHubPreferences.edit().putString(preferenceKey(key), value).apply();
            if (listener != null) listener.onSelected(value);
        }));
    }

    private TextView addActionRowWithDetail(LinearLayout panel, String title, String summary) {
        LinearLayout row = newSettingRow(panel, title, summary, true);
        LinearLayout text = (LinearLayout) row.getChildAt(0);
        TextView detail = (TextView) text.getChildAt(1);
        detail.setTag(row);
        return detail;
    }

    private void addEnvironmentVariablesRow(LinearLayout panel) {
        String current = gameHubPreferences.getString(preferenceKey("general.environment"),
            text(binding.runtimeProfileEnvironment));
        TextView detail = addActionRowWithDetail(panel, "Environment variables", emptyToDash(current));
        View row = (View) detail.getTag();
        row.setOnClickListener(view -> showEnvironmentVariables(detail));
    }

    private void showEnvironmentVariables(TextView detail) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(8), 0, dp(8), 0);
        String current = "—".equals(detail.getText().toString()) ? "" : detail.getText().toString();
        String[] values = current.split("\\n");
        if (values.length == 0 || (values.length == 1 && values[0].isEmpty())) {
            values = new String[] { "VKD3D_DEBUG=", "VKD3D_FEATURE_LEVEL=", "VKD3D_SHADER_MODEL=" };
        }
        List<EditText> editors = new ArrayList<>();
        for (String value : values) {
            EditText editor = new EditText(this);
            editor.setSingleLine(true);
            editor.setText(value);
            editor.setHint("KEY=value");
            editor.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
            fields.addView(editor, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            editors.add(editor);
        }
        new AlertDialog.Builder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle("Environment variables")
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                StringBuilder environment = new StringBuilder();
                for (EditText editor : editors) {
                    String value = editor.getText().toString().trim();
                    if (value.isEmpty()) continue;
                    if (environment.length() > 0) environment.append('\n');
                    environment.append(value);
                }
                String saved = environment.toString();
                gameHubPreferences.edit().putString(preferenceKey("general.environment"), saved).apply();
                detail.setText(emptyToDash(saved));
                binding.runtimeProfileEnvironment.setText(saved);
                refreshDerivedState();
            })
            .show();
    }

    private void showTranslationParameters() {
        String[] schemes = { "Extreme mode", "Performance mode", "Stable mode",
            "Compatibility mode", "Game presets", "Custom" };
        new AlertDialog.Builder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle("Translation parameters")
            .setItems(schemes, (dialog, selected) -> showTranslationPreset(schemes[selected]))
            .show();
    }

    private void showTranslationPreset(String scheme) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        fields.setPadding(dp(14), 0, dp(14), 0);
        String[] settings = { "TSO memory model", "X87 reduced precision", "Multiblock compilation" };
        for (String setting : settings) {
            SwitchCompat toggle = new SwitchCompat(this);
            toggle.setText(setting);
            toggle.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
            toggle.setChecked(gameHubPreferences.getBoolean(preferenceKey("fex." + scheme + "." + setting),
                "Performance mode".equals(scheme) || "Game presets".equals(scheme)));
            toggle.setOnCheckedChangeListener((button, checked) -> gameHubPreferences.edit()
                .putBoolean(preferenceKey("fex." + scheme + "." + setting), checked).apply());
            fields.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        EditText maximum = new EditText(this);
        maximum.setSingleLine(true);
        maximum.setHint("Maximum instructions");
        maximum.setText(gameHubPreferences.getString(preferenceKey("fex." + scheme + ".maxInst"), "5000"));
        maximum.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
        fields.addView(maximum, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(scheme)
            .setView(fields)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> gameHubPreferences.edit()
                .putString(preferenceKey("fex." + scheme + ".maxInst"),
                    maximum.getText().toString().trim()).apply())
            .show();
    }

    private LinearLayout newSettingRow(LinearLayout panel, String title, String summary, boolean chevron) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(2);
        panel.addView(row, params);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        row.addView(text, new LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
        label.setTextSize(14);
        text.addView(label);
        TextView detail = new TextView(this);
        detail.setText(emptyToDash(summary));
        detail.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface_secondary));
        detail.setTextSize(11);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        text.addView(detail);
        if (chevron) {
            TextView icon = new TextView(this);
            icon.setText("›");
            icon.setTextColor(ContextCompat.getColor(this, R.color.local_games_primary));
            icon.setTextSize(26);
            icon.setGravity(android.view.Gravity.CENTER);
            row.addView(icon, new LinearLayout.LayoutParams(dp(26), dp(36)));
        }
        return row;
    }

    private void showChoice(String title, String[] choices, TextView detail,
                            @Nullable ChoiceListener listener) {
        if (choices == null || choices.length == 0) {
            showInfo(title, "No component version is installed yet.");
            return;
        }
        new AlertDialog.Builder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(title)
            .setItems(choices, (dialog, which) -> {
                String value = choices[which];
                detail.setText(value);
                if (listener != null) listener.onSelected(value);
            })
            .show();
    }

    private void showEditor(String title, TextView detail, ChoiceListener listener) {
        EditText input = new EditText(this);
        input.setText("—".equals(detail.getText().toString()) ? "" : detail.getText());
        input.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
        input.setSingleLine(false);
        int padding = dp(22);
        new AlertDialog.Builder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(title)
            .setView(input, padding, 0, padding, 0)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                String value = input.getText().toString().trim();
                detail.setText(emptyToDash(value));
                if (listener != null) listener.onSelected(value);
            })
            .show();
    }

    private void showInfo(String title, String message) {
        new AlertDialog.Builder(this, R.style.ThemeOverlay_TermuxLocalGames_Dialog)
            .setTitle(title).setMessage(message)
            .setPositiveButton(android.R.string.ok, null).show();
    }

    private String[] labels(List<ComponentSelection.Choice> choices) {
        String[] labels = new String[choices.size()];
        for (int index = 0; index < choices.size(); index++) labels[index] = choices.get(index).toString();
        return labels;
    }

    private static String emptyToDash(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }

    private static String joinArguments(Game game) {
        return TextUtils.join(" ", game.getArguments());
    }

    private String preferenceKey(String key) {
        return gameId + "." + key;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface ChoiceListener {
        void onSelected(String value);
    }

    private void applyPreset(RuntimeProfilePreset preset) {
        if (baseline == null) return;
        applyProfile(RuntimeProfilePresets.create(gameId, preset));
    }

    private void applySelectedLegacy() {
        String selected = text(binding.runtimeProfileLegacy);
        for (LegacyRuntimeConfiguration legacy : legacyConfigurations) {
            String label = legacy.getName() + " (" + legacy.getId() + ")";
            if (label.equals(selected)) {
                try {
                    applyProfile(legacyMapper.map(gameId, legacy));
                } catch (IllegalArgumentException error) {
                    showError(getString(R.string.local_game_runtime_profile_invalid,
                        safeMessage(error)));
                }
                return;
            }
        }
    }

    private void applyProfile(RuntimeProfile profile) {
        boundContainerId = profile.getContainerId();
        binding.runtimeProfileBackend.setText(runtimeBackendLabel(
            profile.getRuntimeBackendType()), false);
        binding.runtimeProfileRootfs.setText(profile.getRootfsPackage());
        updateRootfsVisibility(profile.getRuntimeBackendType());
        configureComponentChoices(profile.getRuntimeBackendType());
        setProfileSelection(binding.runtimeProfileWine, wineChoices,
            profile.getWinePackage());
        setProfileSelection(binding.runtimeProfileGraphics, graphicsChoices,
            profile.getGraphicsDriver());
        setProfileSelection(binding.runtimeProfileDx, dxChoices, profile.getDxWrapper());
        binding.runtimeProfileAudio.setText(profile.getAudioDriver());
        binding.runtimeProfileResolution.setText(profile.getResolution());
        binding.runtimeProfileBox64.setText(profile.getBox64Preset());
        binding.runtimeProfileInput.setText(profile.getInputProfileId());
        binding.runtimeProfileExecutionMode.setText(
            executionModeLabel(profile.getLaunchExecutionMode()), false);
        binding.runtimeProfileEnvironment.setText(RuntimeEnvironment.format(
            profile.getEnvironment()));
        formComponentVersions = new LinkedHashMap<>(profile.getComponentVersions());
        refreshDerivedState();
    }

    private void restoreForm(Bundle state) {
        GameRuntimeBackendType restoredBackend;
        try {
            restoredBackend = GameRuntimeBackendType.fromStorageValue(state.getString(
                STATE_PREFIX + "runtimeBackend",
                GameRuntimeBackendType.GLIBC_TERMUX_BOX.getStorageValue()));
        } catch (IllegalArgumentException ignored) {
            restoredBackend = GameRuntimeBackendType.GLIBC_TERMUX_BOX;
        }
        binding.runtimeProfileBackend.setText(runtimeBackendLabel(restoredBackend), false);
        configureComponentChoices(restoredBackend);
        binding.runtimeProfileRootfs.setText(state.getString(STATE_PREFIX + "rootfs", ""));
        updateRootfsVisibility(restoredBackend);
        setProfileSelection(binding.runtimeProfileWine, wineChoices,
            state.getString(STATE_PREFIX + "wine", ""));
        setProfileSelection(binding.runtimeProfileGraphics, graphicsChoices,
            state.getString(STATE_PREFIX + "graphics", ""));
        setProfileSelection(binding.runtimeProfileDx, dxChoices,
            state.getString(STATE_PREFIX + "dx", ""));
        binding.runtimeProfileAudio.setText(state.getString(STATE_PREFIX + "audio", ""));
        binding.runtimeProfileResolution.setText(state.getString(STATE_PREFIX + "resolution", ""));
        binding.runtimeProfileBox64.setText(state.getString(STATE_PREFIX + "box64", ""));
        binding.runtimeProfileInput.setText(state.getString(STATE_PREFIX + "input", ""));
        LaunchExecutionMode restoredMode;
        try {
            restoredMode = LaunchExecutionMode.fromStorageValue(
                state.getString(STATE_PREFIX + "executionMode", "app_shell"));
        } catch (IllegalArgumentException ignored) {
            restoredMode = LaunchExecutionMode.APP_SHELL;
        }
        binding.runtimeProfileExecutionMode.setText(executionModeLabel(restoredMode), false);
        binding.runtimeProfileEnvironment.setText(
            state.getString(STATE_PREFIX + "environment", ""));
        ArrayList<String> keys = state.getStringArrayList(STATE_PREFIX + "componentKeys");
        ArrayList<String> values = state.getStringArrayList(STATE_PREFIX + "componentValues");
        Map<String, String> restored = new LinkedHashMap<>();
        if (keys != null && values != null && keys.size() == values.size()) {
            for (int index = 0; index < keys.size(); index++) {
                restored.put(keys.get(index), values.get(index));
            }
        }
        formComponentVersions = restored;
        // The game profile already owns its container binding.  Do not restore a transient
        // selection by binding an arbitrary existing container from another game.
        boundContainerId = state.getString(STATE_CONTAINER, boundContainerId);
    }

    private void refreshDerivedState() {
        RuntimeProfile profile = profileFromForm(true);
        if (profile == null) return;
        renderChanges(profile);
        runPreflight(profile);
    }

    private void refreshChangesFromForm() {
        if (baseline == null || binding == null) return;
        RuntimeProfile profile = profileFromForm(false);
        if (profile != null) renderChanges(profile);
    }

    private void runPreflightFromForm() {
        RuntimeProfile profile = profileFromForm(true);
        if (profile != null) runPreflight(profile);
    }

    private void runPreflight(RuntimeProfile profile) {
        int generation = ++operationGeneration;
        binding.runtimeProfileProgress.show();
        ioExecutor.execute(() -> {
            LaunchPreflightResult result = null;
            String error = null;
            try {
                result = new AndroidLaunchPreflight(this).evaluate(game, profile,
                    LocalGames.requireHost(this));
            } catch (IOException | RuntimeException failure) {
                error = safeMessage(failure);
            }
            LaunchPreflightResult evaluated = result;
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                binding.runtimeProfileProgress.hide();
                if (failure != null) {
                    showError(getString(R.string.local_game_runtime_profile_load_failed, failure));
                } else {
                    renderPreflight(evaluated);
                }
            });
        });
    }

    private void saveProfile() {
        RuntimeProfile profile = profileFromForm(true);
        if (profile == null) return;
        int generation = ++operationGeneration;
        setFormEnabled(false);
        binding.runtimeProfileProgress.show();
        ioExecutor.execute(() -> {
            String error = null;
            try {
                profileRepository.save(profile);
            } catch (IOException | RuntimeException saveError) {
                error = safeMessage(saveError);
            }
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                binding.runtimeProfileProgress.hide();
                setFormEnabled(true);
                if (failure != null) {
                    showError(getString(R.string.local_game_runtime_profile_save_failed, failure));
                } else {
                    baseline = profile;
                    setResult(Activity.RESULT_OK);
                    renderChanges(profile);
                    boolean glibc = profile.getRuntimeBackendType() ==
                        GameRuntimeBackendType.GLIBC_TERMUX_BOX;
                    if (glibc) PrefixProvisionTasks.enqueue(this, profile.getId());
                    Toast.makeText(this, glibc
                            ? R.string.local_game_runtime_profile_saved_initializing
                            : R.string.local_game_runtime_profile_saved,
                        Toast.LENGTH_SHORT).show();
                    if (!glibc) runPreflight(profile);
                }
            });
        });
    }

    @Nullable
    private RuntimeProfile profileFromForm(boolean showErrors) {
        try {
            RuntimeProfile profile = new RuntimeProfile(gameId,
                selectedProfileValue(binding.runtimeProfileWine, wineChoices),
                selectedProfileValue(binding.runtimeProfileGraphics, graphicsChoices),
                selectedProfileValue(binding.runtimeProfileDx, dxChoices),
                text(binding.runtimeProfileAudio).trim(),
                text(binding.runtimeProfileResolution).trim(),
                text(binding.runtimeProfileBox64).trim(),
                RuntimeEnvironment.parse(text(binding.runtimeProfileEnvironment)),
                text(binding.runtimeProfileInput).trim(), executionModeFromForm(),
                formComponentVersions, runtimeBackendFromForm(),
                text(binding.runtimeProfileRootfs).trim(), boundContainerId);
            clearError();
            return profile;
        } catch (ComponentSelection.UnsupportedSelection error) {
            if (showErrors) {
                showError(getString(R.string.local_game_runtime_profile_component_unsupported,
                    error.getSelection()));
            }
            return null;
        } catch (IllegalArgumentException error) {
            if (showErrors) showError(getString(R.string.local_game_runtime_profile_invalid,
                safeMessage(error)));
            return null;
        }
    }

    private void renderChanges(RuntimeProfile profile) {
        List<RuntimeProfileChange> changes = RuntimeProfileDiff.between(baseline, profile);
        if (changes.isEmpty()) {
            binding.runtimeProfileChanges.setText(R.string.local_game_runtime_profile_no_changes);
            return;
        }
        StringBuilder text = new StringBuilder();
        for (RuntimeProfileChange change : changes) {
            if (text.length() > 0) text.append('\n');
            text.append(getString(R.string.local_game_runtime_profile_change_item,
                change.getField(), change.getBefore(), change.getAfter()));
        }
        binding.runtimeProfileChanges.setText(text);
    }

    private void renderPreflight(LaunchPreflightResult result) {
        binding.runtimeProfilePreflightStatus.setText(result.isReady()
            ? R.string.local_game_runtime_profile_ready
            : R.string.local_game_runtime_profile_blocked);
        binding.runtimeProfilePreflightStatus.setActivated(result.isReady());
        binding.runtimeProfileStorageBudget.setText(getString(
            R.string.local_game_runtime_profile_budget,
            Formatter.formatFileSize(this, result.getStorageBudget().getRequiredBytes()),
            Formatter.formatFileSize(this, result.getStorageBudget().getAvailableBytes())));
        if (result.getIssues().isEmpty()) {
            binding.runtimeProfilePreflightIssues.setText(
                R.string.local_game_runtime_profile_no_issues);
            return;
        }
        StringBuilder issues = new StringBuilder();
        for (PreflightIssue issue : result.getIssues()) {
            if (issues.length() > 0) issues.append('\n');
            issues.append(getString(R.string.local_game_runtime_profile_issue,
                issueMessage(issue)));
        }
        binding.runtimeProfilePreflightIssues.setText(issues);
    }

    private String issueMessage(PreflightIssue issue) {
        switch (issue.getCode()) {
            case PERMISSION_LOST:
                return getString(R.string.local_game_runtime_profile_issue_permission);
            case PROVIDER_UNAVAILABLE:
                return getString(R.string.local_game_runtime_profile_issue_provider);
            case GAME_ROOT_NOT_POSIX_ACCESSIBLE:
                return getString(R.string.local_game_runtime_profile_issue_posix_access);
            case RUNTIME_UNAVAILABLE:
                return getString(R.string.local_game_runtime_profile_issue_runtime);
            case RUNTIME_PROVISION_REQUIRED:
                return getString(R.string.local_game_runtime_profile_issue_provision,
                    issue.getSubject());
            case UNSUPPORTED_PROFILE_SELECTION:
                return getString(R.string.local_game_runtime_profile_issue_selection,
                    issue.getSubject());
            case COMPONENT_UNKNOWN:
                return getString(R.string.local_game_runtime_profile_issue_unknown,
                    issue.getSubject());
            case COMPONENT_VERSION_UNAVAILABLE:
                return getString(R.string.local_game_runtime_profile_issue_unavailable,
                    issue.getSubject());
            case COMPONENT_MISSING:
                return getString(R.string.local_game_runtime_profile_issue_missing,
                    issue.getSubject());
            case COMPONENT_VERSION_MISMATCH:
                return getString(R.string.local_game_runtime_profile_issue_mismatch,
                    issue.getSubject());
            default:
                return getString(R.string.local_game_runtime_profile_issue_storage);
        }
    }

    private void setFormEnabled(boolean enabled) {
        binding.runtimeProfileBackend.setEnabled(enabled);
        binding.runtimeProfileRootfs.setEnabled(enabled);
        binding.runtimeProfileWine.setEnabled(enabled);
        binding.runtimeProfileGraphics.setEnabled(enabled);
        binding.runtimeProfileDx.setEnabled(enabled);
        binding.runtimeProfileAudio.setEnabled(enabled);
        binding.runtimeProfileResolution.setEnabled(enabled);
        binding.runtimeProfileBox64.setEnabled(enabled);
        binding.runtimeProfileInput.setEnabled(enabled);
        binding.runtimeProfileExecutionMode.setEnabled(enabled);
        binding.runtimeProfileEnvironment.setEnabled(enabled);
        binding.runtimeProfileSave.setEnabled(enabled);
        binding.runtimeProfileCheck.setEnabled(enabled);
        binding.runtimeProfilePresetRecommended.setEnabled(enabled);
        binding.runtimeProfilePresetStable.setEnabled(enabled);
        binding.runtimeProfilePresetCompatibility.setEnabled(enabled);
        binding.runtimeProfilePresetCustom.setEnabled(enabled);
        binding.runtimeProfileLegacy.setEnabled(enabled);
        binding.runtimeProfileApplyLegacy.setEnabled(enabled);
        binding.runtimeProfileRestoreDefault.setEnabled(enabled);
        binding.runtimeProfileRestoreSuccess.setEnabled(enabled && lastSuccessful != null);
    }

    private void showError(String message) {
        binding.runtimeProfileError.setText(message);
        binding.runtimeProfileError.setVisibility(View.VISIBLE);
    }

    private void clearError() {
        binding.runtimeProfileError.setVisibility(View.GONE);
        binding.runtimeProfileEnvironmentLayout.setError(null);
    }

    private boolean isCurrent(int generation) {
        return !destroyed && binding != null && generation == operationGeneration;
    }

    private static String text(TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    private String[] executionModeLabels() {
        return new String[] {
            executionModeLabel(LaunchExecutionMode.APP_SHELL),
            executionModeLabel(LaunchExecutionMode.TERMINAL_SESSION)
        };
    }

    private String[] runtimeBackendLabels() {
        return new String[] {
            runtimeBackendLabel(GameRuntimeBackendType.GLIBC_TERMUX_BOX),
            runtimeBackendLabel(GameRuntimeBackendType.ROOTFS_PROOT)
        };
    }

    private String runtimeBackendLabel(GameRuntimeBackendType type) {
        return getString(type == GameRuntimeBackendType.ROOTFS_PROOT
            ? R.string.local_game_runtime_profile_backend_rootfs
            : R.string.local_game_runtime_profile_backend_glibc);
    }

    private GameRuntimeBackendType runtimeBackendFromForm() {
        String selected = text(binding.runtimeProfileBackend);
        if (runtimeBackendLabel(GameRuntimeBackendType.ROOTFS_PROOT).equals(selected)) {
            return GameRuntimeBackendType.ROOTFS_PROOT;
        }
        if (runtimeBackendLabel(GameRuntimeBackendType.GLIBC_TERMUX_BOX).equals(selected)) {
            return GameRuntimeBackendType.GLIBC_TERMUX_BOX;
        }
        throw new IllegalArgumentException("invalid runtimeBackendType");
    }

    private void applyBackendDefaults(GameRuntimeBackendType type) {
        updateRootfsVisibility(type);
        if (type == GameRuntimeBackendType.ROOTFS_PROOT) {
            if (text(binding.runtimeProfileRootfs).trim().isEmpty()) {
                binding.runtimeProfileRootfs.setText(
                    R.string.local_game_runtime_profile_default_rootfs);
            }
            setProfileSelection(binding.runtimeProfileWine, wineChoices,
                getString(R.string.local_game_runtime_profile_default_rootfs_wine));
            setProfileSelection(binding.runtimeProfileGraphics, graphicsChoices,
                getString(R.string.local_game_runtime_profile_default_rootfs_graphics));
            setProfileSelection(binding.runtimeProfileDx, dxChoices,
                getString(R.string.local_game_runtime_profile_default_rootfs_dx));
            if (!"pulseaudio".equals(text(binding.runtimeProfileAudio))) {
                binding.runtimeProfileAudio.setText(
                    R.string.local_game_runtime_profile_default_rootfs_audio);
            }
        } else {
            RuntimeProfile recommended = RuntimeProfilePresets.create(gameId,
                RuntimeProfilePreset.RECOMMENDED);
            binding.runtimeProfileRootfs.setText("");
            setProfileSelection(binding.runtimeProfileWine, wineChoices,
                recommended.getWinePackage());
            setProfileSelection(binding.runtimeProfileGraphics, graphicsChoices,
                recommended.getGraphicsDriver());
            setProfileSelection(binding.runtimeProfileDx, dxChoices,
                recommended.getDxWrapper());
            if ("pulseaudio".equals(text(binding.runtimeProfileAudio))) {
                binding.runtimeProfileAudio.setText(recommended.getAudioDriver());
            }
        }
    }

    private void configureComponentChoices(GameRuntimeBackendType backend) {
        if (backend == GameRuntimeBackendType.ROOTFS_PROOT) {
            wineChoices = ComponentSelection.choices(
                new ComponentSelection.Choice("hangover-11.9", "Hangover 11.9"));
            graphicsChoices = ComponentSelection.choices(
                new ComponentSelection.Choice("rootfs-llvmpipe", "LLVMpipe"),
                new ComponentSelection.Choice("rootfs-virgl-mesa", "VirGL Mesa"));
            dxChoices = ComponentSelection.choices(
                new ComponentSelection.Choice("rootfs-wined3d", "WineD3D"));
        } else {
            wineChoices = catalogChoices(ComponentType.CONTAINER, backend);
            graphicsChoices = catalogChoices(ComponentType.GPU_DRIVER, backend);
            dxChoices = catalogChoices(ComponentType.DX_WRAPPER, backend);
        }
        binding.runtimeProfileWine.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_dropdown_item_1line, wineChoices));
        binding.runtimeProfileGraphics.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_dropdown_item_1line, graphicsChoices));
        binding.runtimeProfileDx.setAdapter(new ArrayAdapter<>(this,
            android.R.layout.simple_dropdown_item_1line, dxChoices));
    }

    private List<ComponentSelection.Choice> catalogChoices(ComponentType type,
                                                           GameRuntimeBackendType backend) {
        if (componentIndex == null) return Collections.emptyList();
        List<ComponentSelection.Choice> result = new ArrayList<>();
        for (ComponentDescriptor descriptor : componentIndex.selectable(type, backend)) {
            result.add(new ComponentSelection.Choice(descriptor.getProfileValue(),
                descriptor.getDisplayName() + " · " + descriptor.getVersionName()));
        }
        return Collections.unmodifiableList(result);
    }

    private static void setProfileSelection(AutoCompleteTextView view,
                                            List<ComponentSelection.Choice> choices,
                                            String value) {
        view.setText(ComponentSelection.labelFor(choices, value), false);
    }

    private static String selectedProfileValue(AutoCompleteTextView view,
                                               List<ComponentSelection.Choice> choices) {
        return ComponentSelection.valueFor(choices, text(view));
    }

    private void updateRootfsVisibility(GameRuntimeBackendType type) {
        binding.runtimeProfileRootfsLayout.setVisibility(
            type == GameRuntimeBackendType.ROOTFS_PROOT ? View.VISIBLE : View.GONE);
    }

    private String executionModeLabel(LaunchExecutionMode mode) {
        return getString(mode == LaunchExecutionMode.TERMINAL_SESSION
            ? R.string.local_game_runtime_profile_execution_terminal
            : R.string.local_game_runtime_profile_execution_app_shell);
    }

    private LaunchExecutionMode executionModeFromForm() {
        String selected = text(binding.runtimeProfileExecutionMode);
        if (executionModeLabel(LaunchExecutionMode.TERMINAL_SESSION).equals(selected)) {
            return LaunchExecutionMode.TERMINAL_SESSION;
        }
        if (executionModeLabel(LaunchExecutionMode.APP_SHELL).equals(selected)) {
            return LaunchExecutionMode.APP_SHELL;
        }
        throw new IllegalArgumentException("invalid launchExecutionMode");
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ++operationGeneration;
        ioExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
