package com.termux.ui.desktop.activity;

import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;
import com.termux.ui.R;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopSessionState;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w840dp-h660dp-land")
public class DesktopGalleryRefreshTest {
    private static DesktopDistro desktop(String resolution) {
        return new DesktopDistro("desktop-1", "XFCE", DesktopRunMode.NATIVE, "", DesktopDe.XFCE4, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, resolution, 30, DesktopUsbStorageMode.OFF);
    }

    private static void render(DesktopActivity activity, DesktopDistro distro,
                               DesktopSessionState state) throws Exception {
        Method method = DesktopActivity.class.getDeclaredMethod("render", List.class, List.class);
        method.setAccessible(true);
        method.invoke(activity, Collections.singletonList(distro), Collections.singletonList(state));
    }

    @Test public void unchangedPollKeepsPressedCardAttached() throws Exception {
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
            DesktopActivity activity = controller.create().get();
            render(activity, desktop("1280x720"), DesktopSessionState.NOT_INSTALLED);
            GridLayout grid = activity.findViewById(R.id.desktop_items);
            View card = grid.getChildAt(0);
            card.setPressed(true);
            // Repository reads return fresh objects even when no fields changed.
            render(activity, desktop("1280x720"), DesktopSessionState.NOT_INSTALLED);
            assertSame(card, grid.getChildAt(0));
            assertTrue(card.isPressed());
            assertSame(grid, card.getParent());
        }
    }

    @Test public void changedStateAndSettingsStillUpdateCards() throws Exception {
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
            DesktopActivity activity = controller.create().get();
            render(activity, desktop("1280x720"), DesktopSessionState.NOT_INSTALLED);
            GridLayout grid = activity.findViewById(R.id.desktop_items);
            View previous = grid.getChildAt(0);
            render(activity, desktop("1280x720"), DesktopSessionState.INSTALLED);
            assertNotSame(previous, grid.getChildAt(0));
            TextView action = grid.getChildAt(0).findViewById(R.id.desktop_primary);
            assertEquals(activity.getString(R.string.desktop_action_open), action.getText().toString());
            previous = grid.getChildAt(0);
            render(activity, desktop("1920x1080"), DesktopSessionState.INSTALLED);
            assertNotSame(previous, grid.getChildAt(0));
        }
    }
}
