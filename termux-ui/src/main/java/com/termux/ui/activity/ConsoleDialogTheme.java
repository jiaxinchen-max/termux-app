package com.termux.ui.activity;

import android.app.Dialog;
import android.content.Context;
import android.os.Build;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.termux.ui.R;
import com.termux.ui.platform.UiTerminalTheme;
import com.termux.view.TerminalView;

/** Reapplies every console layer without replacing the output or the running session. */
final class ConsoleDialogTheme {
    private ConsoleDialogTheme() { }

    static void applyWindow(Dialog dialog) {
        FrostedWindow.applyPanelSurface(dialog.getWindow(), dialog.getContext(),
            R.color.local_games_console_background);
        FrostedWindow.applyScrim(dialog);
        Window window = dialog.getWindow();
        if (window != null) {
            applySystemBars(window, dialog.getContext());
            // Dialog.onStart precedes WindowManager attachment. Apply again once its
            // insets controller belongs to the attached window.
            window.getDecorView().post(() -> applySystemBars(window, dialog.getContext()));
        }
    }

    private static void applySystemBars(Window window, Context context) {
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
            | WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        int color = ContextCompat.getColor(context, Build.VERSION.SDK_INT >= 27
            ? R.color.local_games_background : R.color.local_games_system_bar_legacy);
        window.setStatusBarColor(color);
        window.setNavigationBarColor(color);
        boolean light = Build.VERSION.SDK_INT >= 27
            && context.getResources().getBoolean(R.bool.local_games_light_system_bars);
        WindowInsetsControllerCompat bars = new WindowInsetsControllerCompat(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(light);
        bars.setAppearanceLightNavigationBars(light);
    }

    static void applySetup(Dialog dialog, TerminalView terminal, FollowingLogView log,
                           TextView status, boolean failed) {
        Context context = dialog.getContext();
        applyWindow(dialog);
        UiTerminalTheme.apply(terminal, R.color.local_games_console_background);
        log.applyTheme();
        status.setTextColor(ContextCompat.getColor(context, failed
            ? R.color.local_games_error : R.color.local_games_terminal_secondary));
    }
}
