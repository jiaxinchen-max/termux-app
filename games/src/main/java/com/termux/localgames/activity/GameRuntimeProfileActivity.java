package com.termux.localgames.activity;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Hosts the reusable runtime profile screen when it is opened outside game details. */
public final class GameRuntimeProfileActivity extends AppCompatActivity {

    public static final String EXTRA_GAME_ID = "com.termux.localgames.extra.RUNTIME_GAME_ID";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout host = new FrameLayout(this);
        host.setId(android.R.id.content);
        setContentView(host);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(host.getId(), GameRuntimeProfileFragment.newInstance(
                    getIntent().getStringExtra(GameRuntimeProfileActivity.EXTRA_GAME_ID)))
                .commit();
        }
    }

    @Override
    public void onBackPressed() {
        androidx.fragment.app.Fragment fragment = getSupportFragmentManager()
            .findFragmentById(android.R.id.content);
        if (fragment instanceof GameRuntimeProfileFragment &&
                ((GameRuntimeProfileFragment) fragment).requestBackNavigation()) {
            return;
        }
        super.onBackPressed();
    }
}
