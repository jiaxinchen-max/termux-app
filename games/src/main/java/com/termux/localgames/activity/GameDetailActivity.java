package com.termux.localgames.activity;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.Toast;

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
import com.termux.localgames.recovery.GameUninstallPlan;
import com.termux.localgames.recovery.GameUninstaller;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Private editor for one persisted Game. */
public final class GameDetailActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_ID = "com.termux.localgames.extra.GAME_ID";

    private static final String STATE_NAME = "game_detail.name";
    private static final String STATE_WORKING_DIRECTORY = "game_detail.working_directory";
    private static final String STATE_ARGUMENTS = "game_detail.arguments";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesDetailIo"));
    private final ActivityResultLauncher<Intent> coverPicker = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (result.getResultCode() == Activity.RESULT_OK && data != null &&
                data.getData() != null) replaceCover(data.getData());
        });
    private final ActivityResultLauncher<Intent> reauthorizeLauncher = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == Activity.RESULT_OK) loadGame(true);
            else refreshAccess();
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

        binding.gameDetailToolbar.setNavigationOnClickListener(view -> finish());
        GamesHelpDialog.attach(binding.gameDetailToolbar, R.string.local_game_detail_title,
            R.string.local_game_detail_edit_help);
        binding.gameDetailChangeCover.setOnClickListener(view -> coverPicker.launch(
            new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("image/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
        binding.gameDetailRemoveCover.setOnClickListener(view -> removeCover());
        binding.gameDetailSave.setOnClickListener(view -> saveDetails());
        binding.gameDetailRuntimeProfile.setOnClickListener(view -> {
            if (game != null) startActivity(LocalGames.createRuntimeProfileIntent(this, game.getId()));
        });
        binding.gameDetailAssets.setOnClickListener(view -> {
            if (game != null) startActivity(LocalGames.createGameAssetsIntent(this, game.getId()));
        });
        binding.gameDetailLaunch.setOnClickListener(view -> {
            if (game != null) startActivity(LocalGames.createGameLaunchIntent(this, game.getId()));
        });
        binding.gameDetailDelete.setOnClickListener(view -> confirmDelete());
        binding.gameDetailReauthorize.setOnClickListener(view -> {
            if (game != null) {
                captureFormForRestore();
                reauthorizeLauncher.launch(
                    LocalGames.createReauthorizeIntent(this, game.getRootUri()));
            }
        });

        if (savedInstanceState != null) {
            restoredName = savedInstanceState.getString(STATE_NAME);
            restoredWorkingDirectory = savedInstanceState.getString(STATE_WORKING_DIRECTORY);
            restoredArguments = savedInstanceState.getString(STATE_ARGUMENTS);
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
        renderAccess();
        renderCover(cover);
        binding.gameDetailRemoveCover.setVisibility(
            game.getArtworkUri().isEmpty() ? View.GONE : View.VISIBLE);
        binding.gameDetailLaunch.setEnabled(accessState == GameAccessState.ACCESSIBLE);
        setFormEnabled(true);
        clearErrors();
    }

    private void renderAccess() {
        int title;
        int message;
        boolean reauthorize;
        switch (accessState) {
            case ACCESSIBLE:
                title = R.string.local_game_accessible;
                message = R.string.local_game_detail_accessible_message;
                reauthorize = false;
                break;
            case PERMISSION_LOST:
                title = R.string.local_game_permission_lost;
                message = R.string.local_game_detail_permission_lost_message;
                reauthorize = true;
                break;
            default:
                title = R.string.local_game_provider_unavailable;
                message = R.string.local_game_detail_provider_unavailable_message;
                reauthorize = true;
                break;
        }
        binding.gameDetailAccessStatus.setText(title);
        binding.gameDetailAccessStatus.setTextColor(ContextCompat.getColor(this,
            accessState == GameAccessState.ACCESSIBLE
                ? R.color.local_games_success : R.color.local_games_error));
        binding.gameDetailAccessMessage.setText(message);
        binding.gameDetailReauthorize.setVisibility(reauthorize ? View.VISIBLE : View.GONE);
    }

    private void refreshAccess() {
        Game current = game;
        if (current == null) return;
        int generation = ++operationGeneration;
        ioExecutor.execute(() -> {
            GameAccessState state = accessProbe.check(current);
            runOnUiThread(() -> {
                if (!isCurrent(generation) || game == null ||
                    !game.getId().equals(current.getId())) return;
                accessState = state;
                renderAccess();
            });
        });
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
        binding.gameDetailReauthorize.setEnabled(enabled);
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
    protected void onDestroy() {
        destroyed = true;
        ++operationGeneration;
        ioExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
