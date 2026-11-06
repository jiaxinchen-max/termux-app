package com.termux.ui.platform;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class UiTerminalThemeTest {
    @Test public void switchingAppearanceDoesNotRecolorTheSharedSessionOrTerminalMode() {
        Context base = RuntimeEnvironment.getApplication();
        TerminalEmulator emulator = new TerminalEmulator(new TerminalOutput() {
            public void write(byte[] data, int offset, int count) { }
            public void titleChanged(String oldTitle, String newTitle) { }
            public void onCopyTextToClipboard(String text) { }
            public void onPasteTextFromClipboard() { }
            public void onBell() { }
            public void onColorsChanged() { }
        }, 20, 4, 100, null);
        emulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0xff123456;
        emulator.mColors.mCurrentColors[42] = 0xff345678;
        int[] original = emulator.mColors.mCurrentColors.clone();
        int[] defaults = TerminalColors.COLOR_SCHEME.mDefaultColors.clone();
        TerminalView traditional = new TerminalView(base, null);
        traditional.mEmulator = emulator;
        int[] backgrounds = new int[2];
        int index = 0;
        for (int night : new int[] {Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
            Configuration config = new Configuration(base.getResources().getConfiguration());
            config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
            TerminalView ui = new TerminalView(base.createConfigurationContext(config), null);
            ui.setTextSize(14);
            ui.mEmulator = emulator;
            UiTerminalTheme.apply(ui);
            ui.draw(new Canvas(Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)));
            backgrounds[index++] = ui.getTerminalBackgroundColor();
            assertArrayEquals(original, emulator.mColors.mCurrentColors);
            assertArrayEquals(defaults, TerminalColors.COLOR_SCHEME.mDefaultColors);
            assertEquals(0xff123456, traditional.getTerminalBackgroundColor());
            ui.setColorPaletteOverride(null);
            assertEquals(0xff123456, ui.getTerminalBackgroundColor());
        }
        assertEquals(0xfff3ece3, backgrounds[0]);
        assertEquals(0xff17110d, backgrounds[1]);
    }
}
