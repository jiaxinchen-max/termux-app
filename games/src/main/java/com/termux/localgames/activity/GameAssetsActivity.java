package com.termux.localgames.activity;

import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.localgames.R;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.databinding.ActivityGameAssetsBinding;
import com.termux.localgames.domain.Game;
import com.termux.localgames.recovery.GameAssetCategory;
import com.termux.localgames.recovery.GameAssetInventory;
import com.termux.localgames.recovery.GameAssetInventoryScanner;
import com.termux.localgames.recovery.GameAssetUsage;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Read-only private asset inventory. Global runtime backups live in RuntimeBackupActivity. */
public final class GameAssetsActivity extends AppCompatActivity {
    public static final String EXTRA_GAME_ID = "com.termux.localgames.extra.ASSET_GAME_ID";

    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "GamesAssetInventoryIo"));
    private ActivityGameAssetsBinding binding;
    private FileGameRepository games;
    private GameAssetInventoryScanner inventoryScanner;
    private String gameId;
    private int operationGeneration;
    private boolean destroyed;

    @Override protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityGameAssetsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        gameId = getIntent().getStringExtra(EXTRA_GAME_ID);
        if (gameId == null || !gameId.matches("[A-Za-z0-9._-]{1,128}")) {
            finish();
            return;
        }
        GameStoragePaths paths = new GameStoragePaths(getFilesDir());
        games = new FileGameRepository(paths.getLibraryDirectory());
        inventoryScanner = new GameAssetInventoryScanner(getFilesDir());
        binding.gameAssetsToolbar.setNavigationOnClickListener(view -> finish());
        GamesHelpDialog.attach(binding.gameAssetsToolbar, R.string.local_game_assets_title,
            R.string.local_game_assets_external_protected);
        load();
    }

    private void load() {
        int generation = ++operationGeneration;
        setBusy(true);
        ioExecutor.execute(() -> {
            Game game = null;
            GameAssetInventory inventory = null;
            String error = null;
            try {
                Optional<Game> found = games.find(gameId);
                if (!found.isPresent()) throw new IOException("game_not_found");
                game = found.get();
                inventory = inventoryScanner.scan(gameId);
            } catch (IOException | RuntimeException failure) {
                error = safeMessage(failure);
            }
            Game loadedGame = game;
            GameAssetInventory loadedInventory = inventory;
            String failure = error;
            runOnUiThread(() -> {
                if (!isCurrent(generation)) return;
                setBusy(false);
                if (failure != null) {
                    showError(failure);
                    return;
                }
                binding.gameAssetsContent.setVisibility(View.VISIBLE);
                render(loadedGame, loadedInventory);
            });
        });
    }

    private void render(Game game, GameAssetInventory inventory) {
        binding.gameAssetsError.setVisibility(View.GONE);
        binding.gameAssetsGameName.setText(game.getName());
        binding.gameAssetsTotal.setText(getString(R.string.local_game_assets_total,
            Formatter.formatFileSize(this, inventory.getPrivateBytes())));
        binding.gameAssetsCategories.removeAllViews();
        for (GameAssetUsage usage : inventory.getUsages()) {
            if (usage.getCategory() == GameAssetCategory.EXTERNAL_CONTENT) continue;
            TextView row = new TextView(this);
            row.setPadding(0, dp(8), 0, dp(8));
            int quantity = (int) Math.min(Integer.MAX_VALUE, usage.getFiles());
            row.setText(getString(R.string.local_game_assets_category,
                getString(categoryLabel(usage.getCategory())),
                Formatter.formatFileSize(this, usage.getBytes()),
                getResources().getQuantityString(R.plurals.local_game_assets_file_count,
                    quantity, usage.getFiles()),
                usage.isIncomplete() ? getString(R.string.local_game_assets_incomplete) : ""));
            binding.gameAssetsCategories.addView(row);
        }
    }

    private void setBusy(boolean busy) {
        if (busy) binding.gameAssetsProgress.show(); else binding.gameAssetsProgress.hide();
    }

    private void showError(String error) {
        binding.gameAssetsContent.setVisibility(View.VISIBLE);
        binding.gameAssetsError.setText(getString(R.string.local_game_assets_operation_failed, error));
        binding.gameAssetsError.setVisibility(View.VISIBLE);
    }

    private int categoryLabel(GameAssetCategory category) {
        switch (category) {
            case PREFIX: return R.string.local_game_assets_prefix;
            case CACHE: return R.string.local_game_assets_cache;
            case LOGS: return R.string.local_game_assets_logs;
            case CONFIGURATION: return R.string.local_game_assets_configuration;
            default: return R.string.local_game_assets_external;
        }
    }

    private boolean isCurrent(int generation) {
        return !destroyed && binding != null && generation == operationGeneration;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        ++operationGeneration;
        ioExecutor.shutdownNow();
        binding = null;
        super.onDestroy();
    }
}
