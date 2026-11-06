package com.termux.ui.platform;

import android.content.Context;
import androidx.core.content.ContextCompat;
import androidx.annotation.ColorRes;
import com.termux.terminal.TerminalColorScheme;
import com.termux.terminal.TextStyle;
import com.termux.ui.R;
import com.termux.view.TerminalView;

/** UI-only presentation; never resets or recolors a shared session's emulator. */
public final class UiTerminalTheme {
    private UiTerminalTheme() { }

    public static void apply(TerminalView view) {
        apply(view, R.color.local_games_terminal_background);
    }

    public static void apply(TerminalView view, @ColorRes int background) {
        Context context = view.getContext();
        int[] palette = new TerminalColorScheme().mDefaultColors;
        int[] ansi = context.getResources().getIntArray(R.array.local_games_terminal_ansi);
        System.arraycopy(ansi, 0, palette, 0, 16);
        palette[TextStyle.COLOR_INDEX_BACKGROUND] = ContextCompat.getColor(context, background);
        palette[TextStyle.COLOR_INDEX_FOREGROUND] = ContextCompat.getColor(context, R.color.local_games_terminal_text);
        palette[TextStyle.COLOR_INDEX_CURSOR] = ContextCompat.getColor(context, R.color.local_games_primary);
        view.setColorPaletteOverride(palette);
        view.setBackgroundColor(palette[TextStyle.COLOR_INDEX_BACKGROUND]);
    }
}
