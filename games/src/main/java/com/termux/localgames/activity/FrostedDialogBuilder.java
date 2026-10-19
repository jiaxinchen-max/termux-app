package com.termux.localgames.activity;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.StyleRes;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * The module's Material dialog builder: same API as {@link MaterialAlertDialogBuilder}, but the
 * window background is the single glass fill layer ({@link FrostedWindow#applyDialogSurface}) with
 * zero insets, so the 8 dp radius, the hairline and the API 31+ blur region all come from one
 * shape, and the page behind gets the warm light-mode scrim ({@link FrostedWindow#applyScrim}).
 */
final class FrostedDialogBuilder extends MaterialAlertDialogBuilder {

    FrostedDialogBuilder(@NonNull Context context) {
        super(context);
        removeBackgroundInsets();
    }

    FrostedDialogBuilder(@NonNull Context context, @StyleRes int overrideThemeResId) {
        super(context, overrideThemeResId);
        removeBackgroundInsets();
    }

    private void removeBackgroundInsets() {
        setBackgroundInsetStart(0);
        setBackgroundInsetTop(0);
        setBackgroundInsetEnd(0);
        setBackgroundInsetBottom(0);
    }

    @NonNull
    @Override
    public AlertDialog create() {
        AlertDialog dialog = super.create();
        FrostedWindow.applyDialogSurface(dialog.getWindow(), getContext());
        FrostedWindow.applyScrim(dialog);
        return dialog;
    }
}
