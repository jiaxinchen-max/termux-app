package com.termux.localgames.activity;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.termux.localgames.R;
import com.termux.x11.controller.contentdialog.DialogPalette;

/**
 * Builds the warm Frosted dialog palette handed to the embedded X11 session so the task manager
 * (and its child dialogs) match the other games dialogs in light and dark mode.
 */
final class GameDialogPalettes {
    private GameDialogPalettes() {
    }

    @NonNull
    static DialogPalette frosted(@NonNull Context context) {
        return new DialogPalette(
            ContextCompat.getColor(context, R.color.local_games_overlay_game),
            ContextCompat.getColor(context, R.color.local_games_dialog_outline),
            ContextCompat.getColor(context, R.color.local_games_primary_text),
            ContextCompat.getColor(context, R.color.local_games_on_surface),
            ContextCompat.getColor(context, R.color.local_games_on_surface_secondary),
            ContextCompat.getColor(context, R.color.local_games_surface),
            ContextCompat.getColor(context, R.color.local_games_primary),
            ContextCompat.getColor(context, R.color.local_games_on_primary),
            ContextCompat.getColor(context, R.color.local_games_primary),
            ContextCompat.getColor(context, R.color.local_games_on_primary),
            8f);
    }
}
