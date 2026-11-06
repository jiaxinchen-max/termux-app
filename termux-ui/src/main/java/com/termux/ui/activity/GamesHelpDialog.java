package com.termux.ui.activity;

import android.content.Context;

import androidx.annotation.StringRes;

import com.google.android.material.appbar.MaterialToolbar;
import com.termux.ui.R;

/** Small, explicit entry point for non-essential feature guidance. */
public final class GamesHelpDialog {
    private GamesHelpDialog() { }

    public static void attach(MaterialToolbar toolbar, @StringRes int title, @StringRes int message) {
        toolbar.inflateMenu(R.menu.local_games_help);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.local_games_action_help) return false;
            show(toolbar.getContext(), title, message);
            return true;
        });
    }

    public static void show(Context context, @StringRes int title, @StringRes int message) {
        new FrostedDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    public static void show(Context context, CharSequence title, CharSequence message) {
        new FrostedDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
