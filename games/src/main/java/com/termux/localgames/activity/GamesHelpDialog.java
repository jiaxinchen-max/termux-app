package com.termux.localgames.activity;

import android.content.Context;

import androidx.annotation.StringRes;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.localgames.R;

/** Small, explicit entry point for non-essential feature guidance. */
final class GamesHelpDialog {
    private GamesHelpDialog() { }

    static void attach(MaterialToolbar toolbar, @StringRes int title, @StringRes int message) {
        toolbar.inflateMenu(R.menu.local_games_help);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() != R.id.local_games_action_help) return false;
            show(toolbar.getContext(), title, message);
            return true;
        });
    }

    static void show(Context context, @StringRes int title, @StringRes int message) {
        new MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    static void show(Context context, CharSequence title, CharSequence message) {
        new MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }
}
