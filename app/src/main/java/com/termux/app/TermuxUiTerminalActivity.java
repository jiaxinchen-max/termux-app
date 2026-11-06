package com.termux.app;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.core.content.ContextCompat;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.ui.platform.UiTerminalTheme;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.ui.activity.UiNavigation;
import com.termux.ui.platform.OrientationPreference;
import com.termux.ui.platform.UiThemePreference;

/** The UI Terminal tab reuses Termux sessions without the terminal-mode X11 surface or task reset. */
public final class TermuxUiTerminalActivity extends TermuxActivity {
    private UiNavigation navigation;

    public static Intent createIntent(Context context) {
        return UiNavigation.tabIntent(new Intent(context, TermuxUiTerminalActivity.class))
            .putExtra(EXTRA_SESSION_ONLY, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override protected boolean isUiTerminalPage() { return true; }

    @Override protected void setActivityTheme() {
        UiThemePreference.applyTo(this);
    }

    @Override public void onCreate(@Nullable Bundle state) {
        getIntent().putExtra(EXTRA_SESSION_ONLY, true);
        super.onCreate(state);
        if (!isFinishing() && findViewById(R.id.activity_termux_root_view) != null) {
            // UiNavigation owns system-bar/cutout insets; the terminal must not add them again.
            android.view.View terminalRoot = findViewById(R.id.activity_termux_root_view);
            terminalRoot.setFitsSystemWindows(false);
            terminalRoot.setPadding(0, 0, 0, 0);
            applyTerminalTheme();
            navigation = UiNavigation.install(this,
                com.termux.ui.R.id.local_games_navigation_terminal, null);
        }
    }

    @Override public void onResume() {
        super.onResume();
        UiThemePreference.applyTo(this);
        setRequestedOrientation(OrientationPreference.load(this).getRequestedOrientation());
        applyTerminalTheme();
    }

    private int uiColor(int resource) { return ContextCompat.getColor(this, resource); }

    private void applyTerminalTheme() {
        if (getTerminalView() == null) return;
        UiTerminalTheme.apply(getTerminalView());
        int background = uiColor(com.termux.ui.R.color.local_games_terminal_background);
        getWindow().getDecorView().setBackgroundColor(background);
        View surface = findViewById(R.id.terminal_surface_container);
        if (surface != null) surface.setBackgroundColor(background);
        if (getTerminalToolbarViewPager() != null) styleTerminalToolbar(getTerminalToolbarViewPager());
        if (getExtraKeysView() != null) getExtraKeysView().reload(
            getTermuxTerminalExtraKeys().getExtraKeysInfo(), getTerminalToolbarDefaultHeight());
    }

    @Override public void styleTerminalToolbar(View view) {
        view.setBackgroundColor(uiColor(com.termux.ui.R.color.local_games_terminal_background));
        if (view instanceof ExtraKeysView) {
            ((ExtraKeysView) view).setButtonColors(
                uiColor(com.termux.ui.R.color.local_games_terminal_text),
                uiColor(com.termux.ui.R.color.local_games_primary_text),
                uiColor(com.termux.ui.R.color.local_games_terminal_background),
                uiColor(com.termux.ui.R.color.local_games_selected_surface));
        } else if (view instanceof EditText) {
            EditText input = (EditText) view;
            input.setTextColor(uiColor(com.termux.ui.R.color.local_games_terminal_text));
            input.setHintTextColor(uiColor(com.termux.ui.R.color.local_games_terminal_secondary));
            input.setHighlightColor(uiColor(com.termux.ui.R.color.local_games_nav_indicator));
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleTerminalToolbar(group.getChildAt(i));
        }
    }

    @Override public void finish() {
        super.finish();
        if (navigation != null) overridePendingTransition(0, 0);
    }

    @Override public void onConfigurationChanged(@NonNull Configuration config) {
        super.onConfigurationChanged(config);
        applyTerminalTheme();
        if (navigation != null) navigation.updateConfiguration();
    }
}
