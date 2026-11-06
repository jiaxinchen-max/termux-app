package com.termux.ui.wine.activity;

import android.app.Activity;
import android.net.Uri;
import android.content.Intent;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;

import com.termux.ui.activity.AppUiActivity;
import com.termux.ui.api.LocalGames;
import com.termux.ui.activity.RuntimeComponentVersionsActivity;

import java.util.function.Consumer;

/** Full-screen fallback for the per-game runtime menu on narrow displays. */
public final class GameRuntimeOptionsActivity extends AppUiActivity {

    public static final String EXTRA_GAME_ID = "com.termux.ui.extra.RUNTIME_OPTIONS_GAME_ID";

    private Consumer<String> pendingComponentSelection;
    private final ActivityResultLauncher<Intent> componentSelector = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            Consumer<String> callback = pendingComponentSelection;
            pendingComponentSelection = null;
            if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                String id = result.getData().getStringExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID);
                if (id != null && callback != null) callback.accept(id);
                else if (id != null && this.optionsView != null) this.optionsView.restoreComponentSelection(result.getData());
            }
        });
    private GameRuntimeOptionsView optionsView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        optionsView = new GameRuntimeOptionsView(this,
            getIntent().getStringExtra(EXTRA_GAME_ID), new GameRuntimeOptionsView.Listener() {
                @Override public void onRuntimeOptionsClosed() { finish(); }

                @Override public void onRuntimeOptionsSaved(@Nullable String warmupTaskId,
                                                             boolean rootfs) {
                    startActivity(LocalGames.createLibraryIntentShowingSetupConsole(
                        GameRuntimeOptionsActivity.this, warmupTaskId, rootfs));
                    finish();
                }

                @Override public void onSelectRuntimeComponent(Intent intent, Consumer<String> selected) {
                    pendingComponentSelection = selected;
                    componentSelector.launch(intent);
                }
            });
        setContentView(optionsView);
    }

    @Override
    public void onBackPressed() {
        optionsView.navigateBack();
    }

    @Override
    protected void onDestroy() {
        optionsView.release();
        super.onDestroy();
    }
}
