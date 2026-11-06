package com.termux.ui.activity;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.termux.ui.R;
import com.termux.view.TerminalView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class ConsoleDialogThemeTest {
    @Test public void recolorsOpenConsoleWithoutLosingOutputOrStackingScrims() {
        RuntimeEnvironment.setQualifiers("+notnight");
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            Activity host = controller.get();
            host.setTheme(R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
            controller.setup().visible();
            Dialog dialog = new Dialog(host);
            LinearLayout content = new LinearLayout(dialog.getContext());
            TerminalView terminal = new TerminalView(dialog.getContext(), null);
            FollowingLogView log = new FollowingLogView(dialog.getContext());
            TextView status = new TextView(dialog.getContext());
            log.setOutput("Existing installation output\nSecond line");
            content.addView(log);
            content.addView(status);
            dialog.setContentView(content);
            ViewGroup hostDecor = (ViewGroup) host.getWindow().getDecorView();
            int originalChildren = hostDecor.getChildCount();
            dialog.show();
            ShadowLooper.idleMainLooper();
            try {
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
                ConsoleDialogTheme.applySetup(dialog, terminal, log, status, false);
                ShadowLooper.idleMainLooper();
                assertTrue((dialog.getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0);
                assertEquals(0, dialog.getWindow().getAttributes().flags
                    & (WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_BLUR_BEHIND));
                assertEquals(0xfff7f1e8, terminal.getTerminalBackgroundColor());
                assertEquals(originalChildren + 1, hostDecor.getChildCount());
                ConsoleDialogTheme.applySetup(dialog, terminal, log, status, false);
                assertEquals(originalChildren + 1, hostDecor.getChildCount());

                RuntimeEnvironment.setQualifiers("+night");
                ConsoleDialogTheme.applySetup(dialog, terminal, log, status, true);
                ShadowLooper.idleMainLooper();
                assertEquals(0, dialog.getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                assertEquals(0xff120d0a, terminal.getTerminalBackgroundColor());
                assertEquals(originalChildren, hostDecor.getChildCount());
                assertTrue((dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0);
                assertEquals(host.getColor(R.color.local_games_error), status.getCurrentTextColor());
                assertEquals(0xff120d0a, ((GradientDrawable) dialog.getWindow().getDecorView().getBackground()).getColor().getDefaultColor());
                assertEquals("Existing installation output\nSecond line", ((TextView) log.getChildAt(0)).getText().toString());

                RuntimeEnvironment.setQualifiers("+notnight");
                ConsoleDialogTheme.applySetup(dialog, terminal, log, status, false);
                ShadowLooper.idleMainLooper();
                assertTrue((dialog.getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) != 0);
                assertEquals(0, dialog.getWindow().getAttributes().flags
                    & (WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_BLUR_BEHIND));
                assertEquals(0xfff7f1e8, terminal.getTerminalBackgroundColor());
                assertEquals(originalChildren + 1, hostDecor.getChildCount());
            } finally {
                dialog.dismiss();
            }
            assertEquals(originalChildren, hostDecor.getChildCount());
        }
    }
}
