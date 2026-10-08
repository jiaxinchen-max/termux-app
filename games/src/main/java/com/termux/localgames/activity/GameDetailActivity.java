package com.termux.localgames.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.text.format.Formatter;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.localgames.R;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.artwork.GameArtworkLoader;
import com.termux.localgames.artwork.GameArtworkStore;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.GameAccessState;
import com.termux.localgames.data.GameRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.databinding.ActivityGameDetailBinding;
import com.termux.localgames.domain.Game;
import com.termux.localgames.importer.GameImportValidation;
import com.termux.localgames.importer.LaunchArguments;
import com.termux.localgames.importer.SafGameAccessProbe;
import com.termux.localgames.recovery.GameAssetCategory;
import com.termux.localgames.recovery.GameAssetInventory;
import com.termux.localgames.recovery.GameAssetInventoryScanner;
import com.termux.localgames.recovery.GameAssetUsage;
import com.termux.localgames.recovery.GameUninstallPlan;
import com.termux.localgames.recovery.GameUninstaller;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Private editor for one persisted Game. */
public final class GameDetailActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_ID = "com.termux.localgames.extra.GAME_ID";

    private static final String STATE_NAME = "game_detail.name";
    private static final String STATE_WORKING_DIRECTORY = "game_detail.working_directory";
    private static final String STATE_ARGUMENTS = "game_detail.arguments";
    private static final String STATE_SIDE_PANEL = "game_detail.side_panel";
    private static final String SIDE_PANEL_EDITOR = "editor";
    private static final String SIDE_PANEL_STORAGE = "storage";
    private static final String SIDE_PANEL_RUNTIME = "runtime";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesDetailIo"));
    private final ActivityResultLauncher<Intent> coverPicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (result.getResultCode() == Activity.RESULT_OK && data != null &&
                data.getData() != null) replaceCover(data.getData());
        });
    private Consumer<Uri> pendingCustomComponentPick;
    private final ActivityResultLauncher<String[]> customComponentPicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            Consumer<Uri> callback = pendingCustomComponentPick;
            pendingCustomComponentPick = null;
            if (callback != null) callback.accept(uri);
        });
    private ActivityGameDetailBinding binding;
    private GameRepository gameRepository;
    private SafGameAccessProbe accessProbe;
    private GameArtworkStore artworkStore;
    private GameArtworkLoader artworkLoader;
    private String gameId;
    private Game game;
    private GameAccessState accessState;
    private int operationGeneration;
    private boolean destroyed;
    private String restoredName;
    private String restoredWorkingDirectory;
    private String restoredArguments;
    private String sidePanel = SIDE_PANEL_EDITOR;
    private int sidePanelGeneration;
    private GameRuntimeOptionsView runtimeOptionsView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGameDetailBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        gameId = getIntent().getStringExtra(EXTRA_GAME_ID);
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        gameRepository = new FileGameRepository(paths.getLibraryDirectory());
        accessProbe = new SafGameAccessProbe(getContentResolver());
        artworkStore = new GameArtworkStore(getFilesDir());
        artworkLoader = new GameArtworkLoader(artworkStore);

        binding.gameDetailChangeCover.setOnClickListener(view -> coverPicker.launch(
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
        binding.gameDetailRemoveCover.setOnClickListener(view -> removeCover());
        binding.gameDetailSave.setOnClickListener(view -> saveDetails());
        binding.gameDetailRuntimeProfile.setOnClickListener(view -> openRuntime());
        binding.gameDetailAssets.setOnClickListener(view -> openStorage());
        binding.gameDetailLaunch.setOnClickListener(view -> {
            if (game != null) startActivity(LocalGames.createGameLaunchIntent(this, game.getId()));
        });
        binding.gameDetailDelete.setOnClickListener(view -> confirmDelete());
        if (savedInstanceState != null) {
            restoredName = savedInstanceState.getString(STATE_NAME);
            restoredWorkingDirectory = savedInstanceState.getString(STATE_WORKING_DIRECTORY);
            restoredArguments = savedInstanceState.getString(STATE_ARGUMENTS);
            sidePanel = savedInstanceState.getString(STATE_SIDE_PANEL, SIDE_PANEL_EDITOR);
        }
        if (TextUtils.isEmpty(gameId)) {
            Toast.makeText(this, R.string.local_game_detail_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        loadGame(true);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (game != null && binding != null) {
            outState.putString(STATE_NAME, textOf(binding.gameDetailName));
            outState.putString(STATE_WORKING_DIRECTORY,
                textOf(binding.gameDetailWorkingDirectory));
            outState.putString(STATE_ARGUMENTS, textOf(binding.gameDetailArguments));
            outState.putString(STATE_SIDE_PANEL, sidePanel);
        }
        super.onSaveInstanceState(outState);
    }

    private void loadGame(boolean applyRestoredForm) {
        int generation = ++operationGeneration;
        binding.gameDetailProgress.show();
        binding.gameDetailContent.setVisibility(View.GONE);
        ioExecutor.execute(() -> {
            Optional<Game> found = Optional.empty();
            GameAccessState loadedAccess = null;
            Bitmap cover = null;
            String error = null;
            try {
                found = gameRepository.find(gameId);
                if (found.isPresent()) {
                    loadedAccess = accessProbe.check(found.get());
                    try {
                        artworkStore.recover(gameId);
                    } catch (IOException ignored) {
                    }
                    cover = artworkLoader.load(found.get().getArtworkUri(), 512);
                }
            } catch (IOException | RuntimeException loadError) {
                error = safeMessage(loadError);
            }
            Optional<Game> loaded = found;
            GameAccessState state = loadedAccess;
            Bitmap artwork = cover;
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                binding.gameDetailProgress.hide();
                if (failure != null) {
                    Toast.makeText(this, getString(R.string.local_games_library_load_failed,
                        failure), Toast.LENGTH_LONG).show();
                    finish();
                } else if (!loaded.isPresent()) {
                    Toast.makeText(this, R.string.local_game_detail_missing,
                        Toast.LENGTH_SHORT).show();
                    finish();
                } else {
                    game = loaded.get();
                    accessState = state;
                    renderGame(artwork, applyRestoredForm);
                }
            });
        });
    }

    private void renderGame(@Nullable Bitmap cover, boolean applyRestoredForm) {
        binding.gameDetailContent.setVisibility(View.VISIBLE);
        binding.gameDetailTitleName.setText(game.getName());
        binding.gameDetailExecutable.setText(getString(
            R.string.local_game_detail_executable, game.getExecutable()));
        binding.gameDetailRootUri.setText(getString(
            R.string.local_game_detail_root, game.getRootUri()));
        binding.gameDetailName.setText(applyRestoredForm && restoredName != null
            ? restoredName : game.getName());
        binding.gameDetailWorkingDirectory.setText(
            applyRestoredForm && restoredWorkingDirectory != null
                ? restoredWorkingDirectory : game.getWorkingDirectory());
        binding.gameDetailArguments.setText(applyRestoredForm && restoredArguments != null
            ? restoredArguments : LaunchArguments.format(game.getArguments()));
        restoredName = null;
        restoredWorkingDirectory = null;
        restoredArguments = null;
        renderCover(cover);
        binding.gameDetailRemoveCover.setVisibility(
            game.getArtworkUri().isEmpty() ? View.GONE : View.VISIBLE);
        binding.gameDetailLaunch.setEnabled(accessState == GameAccessState.ACCESSIBLE);
        setFormEnabled(true);
        clearErrors();
        if (SIDE_PANEL_STORAGE.equals(sidePanel)) showStoragePanel();
        else if (SIDE_PANEL_RUNTIME.equals(sidePanel)) showRuntimePanel();
        else showEditorPanel();
    }

    private void openRuntime() {
        Game current = game;
        if (current == null) return;
        if (hasEmbeddedSidePanels()) {
            showRuntimePanel();
        } else {
            startActivity(LocalGames.createRuntimeOptionsIntent(this, current.getId()));
        }
    }

    private void openStorage() {
        if (game == null) return;
        if (hasEmbeddedSidePanels()) {
            showStoragePanel();
        } else {
            startActivity(LocalGames.createGameAssetsIntent(this, game.getId()));
        }
    }

    private boolean hasEmbeddedSidePanels() {
        return findViewById(R.id.game_detail_editor_panel) != null;
    }

    private void showEditorPanel() {
        if (!hasEmbeddedSidePanels()) return;
        sidePanel = SIDE_PANEL_EDITOR;
        setSidePanelVisibility(View.VISIBLE, View.GONE, View.GONE);
    }

    private void showStoragePanel() {
        if (game == null || !hasEmbeddedSidePanels()) return;
        sidePanel = SIDE_PANEL_STORAGE;
        setSidePanelVisibility(View.GONE, View.VISIBLE, View.GONE);
        TextView total = findViewById(R.id.game_detail_storage_total);
        TextView error = findViewById(R.id.game_detail_storage_error);
        LinearLayout categories = findViewById(R.id.game_detail_storage_categories);
        total.setText(R.string.local_game_detail_side_panel_loading);
        error.setVisibility(View.GONE);
        categories.removeAllViews();
        int generation = ++sidePanelGeneration;
        String currentGameId = game.getId();
        binding.gameDetailProgress.show();
        ioExecutor.execute(() -> {
            GameAssetInventory inventory = null;
            String failure = null;
            try {
                inventory = new GameAssetInventoryScanner(getFilesDir()).scan(currentGameId);
            } catch (IOException | RuntimeException loadError) {
                failure = safeMessage(loadError);
            }
            GameAssetInventory loaded = inventory;
            String errorMessage = failure;
            runOnUiThread(() -> {
                if (!isCurrentSidePanel(generation, SIDE_PANEL_STORAGE)) return;
                binding.gameDetailProgress.hide();
                renderStoragePanel(loaded, errorMessage);
            });
        });
    }

    private void renderStoragePanel(@Nullable GameAssetInventory inventory,
                                    @Nullable String errorMessage) {
        TextView total = findViewById(R.id.game_detail_storage_total);
        TextView error = findViewById(R.id.game_detail_storage_error);
        LinearLayout categories = findViewById(R.id.game_detail_storage_categories);
        categories.removeAllViews();
        if (inventory == null) {
            total.setText(R.string.local_game_detail_side_panel_unavailable);
            error.setText(getString(R.string.local_game_assets_operation_failed,
                errorMessage == null ? "unknown" : errorMessage));
            error.setVisibility(View.VISIBLE);
            return;
        }
        total.setText(getString(R.string.local_game_assets_total,
            Formatter.formatFileSize(this, inventory.getPrivateBytes())));
        error.setVisibility(View.GONE);
        for (GameAssetUsage usage : inventory.getUsages()) {
            if (usage.getCategory() == GameAssetCategory.EXTERNAL_CONTENT) continue;
            int quantity = (int) Math.min(Integer.MAX_VALUE, usage.getFiles());
            addSidePanelRow(categories, getString(assetCategoryLabel(usage.getCategory())),
                getString(R.string.local_game_assets_category,
                    getString(assetCategoryLabel(usage.getCategory())),
                    Formatter.formatFileSize(this, usage.getBytes()),
                    getResources().getQuantityString(R.plurals.local_game_assets_file_count,
                        quantity, usage.getFiles()),
                    usage.isIncomplete()
                        ? getString(R.string.local_game_assets_incomplete) : ""));
        }
    }

    private void showRuntimePanel() {
        if (game == null || !hasEmbeddedSidePanels()) return;
        sidePanel = SIDE_PANEL_RUNTIME;
        setSidePanelVisibility(View.GONE, View.GONE, View.VISIBLE);
        if (runtimeOptionsView != null) return;
        FrameLayout panel = findViewById(R.id.game_detail_runtime_panel);
        runtimeOptionsView = new GameRuntimeOptionsView(this, game.getId(),
            new GameRuntimeOptionsView.Listener() {
                @Override public void onRuntimeOptionsClosed() { closeRuntimePanel(); }

                @Override public void onRuntimeOptionsSaved(@Nullable String warmupTaskId,
                                                             boolean rootfs) {
                    startActivity(LocalGames.createLibraryIntentShowingSetupConsole(
                        GameDetailActivity.this, warmupTaskId, rootfs));
                    finish();
                }

                @Override public void onPickCustomComponentFile(String[] mimeTypes,
                                                                  Consumer<Uri> onPicked) {
                    pendingCustomComponentPick = onPicked;
                    customComponentPicker.launch(mimeTypes);
                }
            });
        runtimeOptionsView.setShowHeader(false);
        panel.addView(runtimeOptionsView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void setSidePanelVisibility(int editorVisibility, int storageVisibility,
                                        int runtimeVisibility) {
        findViewById(R.id.game_detail_editor_panel).setVisibility(editorVisibility);
        findViewById(R.id.game_detail_storage_panel).setVisibility(storageVisibility);
        findViewById(R.id.game_detail_runtime_panel).setVisibility(runtimeVisibility);
    }

    private void closeRuntimePanel() {
        if (runtimeOptionsView != null) {
            FrameLayout panel = findViewById(R.id.game_detail_runtime_panel);
            panel.removeView(runtimeOptionsView);
            runtimeOptionsView.release();
            runtimeOptionsView = null;
        }
        if (!destroyed) showEditorPanel();
    }

    private boolean isCurrentSidePanel(int generation, String expectedPanel) {
        return !destroyed && generation == sidePanelGeneration && expectedPanel.equals(sidePanel);
    }

    private void addSidePanelRow(LinearLayout target, String label, String value) {
        TextView row = new TextView(this);
        row.setPadding(0, dp(8), 0, dp(8));
        row.setText(getString(R.string.local_game_detail_side_panel_row, label,
            TextUtils.isEmpty(value) ? "—" : value));
        row.setTextColor(ContextCompat.getColor(this, R.color.local_games_on_surface));
        row.setTextSize(13);
        target.addView(row, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private int assetCategoryLabel(GameAssetCategory category) {
        switch (category) {
            case PREFIX: return R.string.local_game_assets_prefix;
            case CACHE: return R.string.local_game_assets_cache;
            case LOGS: return R.string.local_game_assets_logs;
            case CONFIGURATION: return R.string.local_game_assets_configuration;
            default: return R.string.local_game_assets_external;
        }
    }

    private void saveDetails() {
        if (game == null) return;
        clearErrors();
        String name = textOf(binding.gameDetailName).trim();
        String workingDirectory = textOf(binding.gameDetailWorkingDirectory).trim();
        List<String> arguments;
        boolean valid = true;
        if (name.isEmpty()) {
            binding.gameDetailNameLayout.setError(getString(R.string.local_game_import_invalid_name));
            valid = false;
        }
        if (!GameImportValidation.isSafeRelativeDirectory(workingDirectory)) {
            binding.gameDetailWorkingDirectoryLayout.setError(
                getString(R.string.local_game_import_invalid_working_directory));
            valid = false;
        }
        try {
            arguments = LaunchArguments.parse(textOf(binding.gameDetailArguments));
        } catch (IllegalArgumentException error) {
            arguments = new ArrayList<>();
            binding.gameDetailArgumentsLayout.setError(
                getString(R.string.local_game_import_invalid_arguments));
            valid = false;
        }
        if (!valid) return;

        Game updated = new Game(game.getId(), name, game.getRootUri(), game.getExecutable(),
            workingDirectory, arguments, game.getArtworkUri(), game.getLastPlayedAt());
        persistGame(updated, R.string.local_game_detail_saved);
    }

    private void persistGame(Game updated, int successMessage) {
        int generation = ++operationGeneration;
        setFormEnabled(false);
        binding.gameDetailProgress.show();
        ioExecutor.execute(() -> {
            String error = null;
            try {
                gameRepository.save(updated);
            } catch (IOException | RuntimeException saveError) {
                error = safeMessage(saveError);
            }
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                binding.gameDetailProgress.hide();
                setFormEnabled(true);
                if (failure != null) {
                    showError(getString(R.string.local_game_detail_save_failed, failure));
                } else {
                    game = updated;
                    setResult(Activity.RESULT_OK);
                    Toast.makeText(this, successMessage, Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void replaceCover(Uri sourceUri) {
        if (game == null) return;
        Game current = game;
        int generation = ++operationGeneration;
        setFormEnabled(false);
        binding.gameDetailProgress.show();
        ioExecutor.execute(() -> {
            Game updated = null;
            Bitmap cover = null;
            String error = null;
            try (InputStream input = getContentResolver().openInputStream(sourceUri)) {
                if (input == null) throw new IOException("cover_provider_returned_null_stream");
                try (GameArtworkStore.Mutation mutation =
                         artworkStore.stageReplacement(current.getId(), input)) {
                    updated = copyWithArtwork(current, mutation.getReference());
                    gameRepository.save(updated);
                    mutation.commit();
                    cover = artworkLoader.load(updated.getArtworkUri(), 512);
                }
            } catch (IOException | RuntimeException coverError) {
                error = safeMessage(coverError);
            }
            Game saved = updated;
            Bitmap loadedCover = cover;
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                binding.gameDetailProgress.hide();
                setFormEnabled(true);
                if (failure != null) {
                    showError(getString(R.string.local_game_detail_cover_failed, failure));
                } else {
                    game = saved;
                    setResult(Activity.RESULT_OK);
                    renderCover(loadedCover);
                    binding.gameDetailRemoveCover.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private void removeCover() {
        if (game == null || game.getArtworkUri().isEmpty()) return;
        Game current = game;
        int generation = ++operationGeneration;
        setFormEnabled(false);
        binding.gameDetailProgress.show();
        ioExecutor.execute(() -> {
            Game updated = null;
            String error = null;
            try (GameArtworkStore.Mutation mutation = artworkStore.stageRemoval(current.getId())) {
                updated = copyWithArtwork(current, "");
                gameRepository.save(updated);
                mutation.commit();
            } catch (IOException | RuntimeException removeError) {
                error = safeMessage(removeError);
            }
            Game saved = updated;
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                binding.gameDetailProgress.hide();
                setFormEnabled(true);
                if (failure != null) {
                    showError(getString(R.string.local_game_detail_cover_failed, failure));
                } else {
                    game = saved;
                    setResult(Activity.RESULT_OK);
                    renderCover(null);
                    binding.gameDetailRemoveCover.setVisibility(View.GONE);
                }
            });
        });
    }

    private void confirmDelete() {
        if (game == null) return;
        String[] options = {
            getString(R.string.local_game_detail_uninstall_prefix),
            getString(R.string.local_game_detail_uninstall_cache),
            getString(R.string.local_game_detail_uninstall_logs),
            getString(R.string.local_game_detail_uninstall_configuration)
        };
        boolean[] selections = new boolean[options.length];
        new MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.local_game_detail_uninstall_title, game.getName()))
            .setMultiChoiceItems(options, selections,
                (dialog, which, checked) -> selections[which] = checked)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.local_game_detail_delete_confirm,
                (dialog, which) -> deleteGame(GameUninstallPlan.keepPrivateAssets(game.getId())
                    .withSelections(selections[0], selections[1], selections[2], selections[3])))
            .show();
    }

    private void deleteGame(GameUninstallPlan plan) {
        Game current = game;
        if (current == null) return;
        int generation = ++operationGeneration;
        setFormEnabled(false);
        binding.gameDetailProgress.show();
        ioExecutor.execute(() -> {
            String error = null;
            try {
                new GameUninstaller(getFilesDir()).execute(plan);
            } catch (IOException | RuntimeException deleteError) {
                error = safeMessage(deleteError);
            }
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                if (failure != null) {
                    binding.gameDetailProgress.hide();
                    setFormEnabled(true);
                    showError(getString(R.string.local_game_detail_delete_failed, failure));
                } else {
                    setResult(Activity.RESULT_OK);
                    finish();
                }
            });
        });
    }

    private void renderCover(@Nullable Bitmap cover) {
        if (cover == null) {
            binding.gameDetailCover.setPadding(dp(72), dp(72), dp(72), dp(72));
            binding.gameDetailCover.setImageResource(R.drawable.local_games_cover_placeholder);
        } else {
            binding.gameDetailCover.setPadding(0, 0, 0, 0);
            binding.gameDetailCover.setImageBitmap(cover);
        }
    }

    private void setFormEnabled(boolean enabled) {
        binding.gameDetailName.setEnabled(enabled);
        binding.gameDetailWorkingDirectory.setEnabled(enabled);
        binding.gameDetailArguments.setEnabled(enabled);
        binding.gameDetailChangeCover.setEnabled(enabled);
        binding.gameDetailRemoveCover.setEnabled(enabled);
        binding.gameDetailSave.setEnabled(enabled);
        binding.gameDetailLaunch.setEnabled(enabled && accessState == GameAccessState.ACCESSIBLE);
        binding.gameDetailRuntimeProfile.setEnabled(enabled);
        binding.gameDetailAssets.setEnabled(enabled);
        binding.gameDetailDelete.setEnabled(enabled);
    }

    private void clearErrors() {
        binding.gameDetailNameLayout.setError(null);
        binding.gameDetailWorkingDirectoryLayout.setError(null);
        binding.gameDetailArgumentsLayout.setError(null);
        binding.gameDetailError.setVisibility(View.GONE);
    }

    private void captureFormForRestore() {
        restoredName = textOf(binding.gameDetailName);
        restoredWorkingDirectory = textOf(binding.gameDetailWorkingDirectory);
        restoredArguments = textOf(binding.gameDetailArguments);
    }

    private void showError(String error) {
        binding.gameDetailError.setText(error);
        binding.gameDetailError.setVisibility(View.VISIBLE);
    }

    private boolean isCurrent(int generation) {
        return !destroyed && binding != null && generation == operationGeneration;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static Game copyWithArtwork(Game game, String artworkUri) {
        return new Game(game.getId(), game.getName(), game.getRootUri(), game.getExecutable(),
            game.getWorkingDirectory(), game.getArguments(), artworkUri, game.getLastPlayedAt());
    }

    private static String textOf(android.widget.TextView view) {
        return view.getText() == null ? "" : view.getText().toString();
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override
    public void onBackPressed() {
        if (hasEmbeddedSidePanels() && SIDE_PANEL_RUNTIME.equals(sidePanel) &&
            runtimeOptionsView != null) {
            runtimeOptionsView.navigateBack();
            return;
        }
        if (hasEmbeddedSidePanels() && !SIDE_PANEL_EDITOR.equals(sidePanel)) {
            ++sidePanelGeneration;
            showEditorPanel();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ++operationGeneration;
        ++sidePanelGeneration;
        if (runtimeOptionsView != null) runtimeOptionsView.release();
        ioExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
