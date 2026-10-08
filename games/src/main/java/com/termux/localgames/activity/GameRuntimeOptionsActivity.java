package com.termux.localgames.activity;

import android.net.Uri;
import android.os.Bundle;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.localgames.api.LocalGames;

import java.util.function.Consumer;

/** Full-screen fallback for the per-game runtime menu on narrow displays. */
public final class GameRuntimeOptionsActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_ID = "com.termux.localgames.extra.RUNTIME_OPTIONS_GAME_ID";

    private Consumer<Uri> pendingCustomComponentPick;
    private final ActivityResultLauncher<String[]> customComponentPicker = registerForActivityResult(
        new ActivityResultContracts.OpenDocument(), uri -> {
            Consumer<Uri> callback = pendingCustomComponentPick;
            pendingCustomComponentPick = null;
            if (callback != null) callback.accept(uri);
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

                @Override public void onPickCustomComponentFile(String[] mimeTypes,
                                                                  Consumer<Uri> onPicked) {
                    pendingCustomComponentPick = onPicked;
                    customComponentPicker.launch(mimeTypes);
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
