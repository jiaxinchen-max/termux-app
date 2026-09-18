package com.termux.localgames.activity;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Full-screen fallback for the per-game runtime menu on narrow displays. */
public final class GameRuntimeOptionsActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_ID = "com.termux.localgames.extra.RUNTIME_OPTIONS_GAME_ID";

    private GameRuntimeOptionsView optionsView;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        optionsView = new GameRuntimeOptionsView(this,
            getIntent().getStringExtra(EXTRA_GAME_ID), this::finish);
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
