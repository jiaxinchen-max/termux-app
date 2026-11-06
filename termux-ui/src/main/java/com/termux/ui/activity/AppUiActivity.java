package com.termux.ui.activity;

import android.os.Bundle;
import android.content.Intent;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.ui.platform.OrientationPreference;
import com.termux.ui.platform.UiThemePreference;
import com.termux.ui.R;

/** UI pages apply their saved appearance and orientation when created or resumed. */
public abstract class AppUiActivity extends AppCompatActivity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        UiThemePreference.applyTo(this);
        super.onCreate(savedInstanceState);
        applyAppOrientation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        UiThemePreference.applyTo(this);
        applyAppOrientation();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void finish() {
        boolean rootPage = findViewById(R.id.ui_navigation_content) != null;
        super.finish();
        if (rootPage) overridePendingTransition(0, 0);
    }

    private void applyAppOrientation() {
        setRequestedOrientation(OrientationPreference.load(this).getRequestedOrientation());
    }
}
