package com.termux.ui.desktop.activity;

import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;
import android.view.View;
import android.content.Intent;
import android.content.DialogInterface;
import androidx.appcompat.app.AlertDialog;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowDialog;
import java.lang.reflect.Field;
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
import com.termux.ui.activity.FileManagerViewMode;
import org.robolectric.RuntimeEnvironment;
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
            assertEquals(activity.getString(R.string.desktop_action_start), action.getText().toString());
            previous = grid.getChildAt(0);
            render(activity, desktop("1920x1080"), DesktopSessionState.INSTALLED);
            assertNotSame(previous, grid.getChildAt(0));
        }
    }
    private static void confirm(DesktopActivity activity, DesktopDistro desktop, boolean running) throws Exception {
        Method method = DesktopActivity.class.getDeclaredMethod("confirmOpen", DesktopDistro.class, boolean.class);
        method.setAccessible(true);
        method.invoke(activity, desktop, running);
    }

    @Test public void cardBodyIsInertAndStartingOrStoppingDisablesPrimary() throws Exception {
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
            DesktopActivity activity = controller.create().get();
            for (DesktopSessionState state : new DesktopSessionState[]{DesktopSessionState.INSTALLED,
                    DesktopSessionState.STARTING, DesktopSessionState.STOPPING}) {
                render(activity, desktop("1280x720"), state);
                GridLayout grid = activity.findViewById(R.id.desktop_items);
                View card = grid.getChildAt(0);
                assertFalse(card.performClick());
                assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                assertEquals(state == DesktopSessionState.INSTALLED,
                    card.findViewById(R.id.desktop_primary).isEnabled());
            }
        }
    }

    @Test @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    public void fixedCardsKeepActionsInsideFiniteViewportsWithLongNamesAndLargeFonts() throws Exception {
        for (float scale : new float[]{1f, 2f}) {
            RuntimeEnvironment.setFontScale(scale);
            FileManagerViewMode.GRID.save(RuntimeEnvironment.getApplication(), "desktop");
            RuntimeEnvironment.getApplication().getSharedPreferences("local_games_settings", 0)
                .edit().putBoolean("desktop_proot", true).commit();
            try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
                DesktopActivity activity = controller.create().get();
                DesktopDistro desktop = new DesktopDistro("long-name", "Ubuntu GNOME with my work applications",
                    DesktopRunMode.PROOT, "ubuntu-ubuntu-gnome-1", DesktopDe.UBUNTU_GNOME,
                    DesktopGlMode.SYSTEM, DesktopGuestGlMode.LLVMPIPE, "1280x720", 60, DesktopUsbStorageMode.OFF);
                GridLayout grid = activity.findViewById(R.id.desktop_items);
                float density = activity.getResources().getDisplayMetrics().density;
                for (int widthDp : new int[]{240, 360, 840}) {
                    int width = Math.round(widthDp * density);
                    grid.layout(0, 0, width, 1);
                    render(activity, desktop, DesktopSessionState.INSTALLING);
                    // Text auto-sizing can request another layout after the first finite pass.
                    for (int pass = 0; pass < 2; pass++) {
                        grid.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(Math.round(660 * density), View.MeasureSpec.AT_MOST));
                        grid.layout(0, 0, width, grid.getMeasuredHeight());
                    }
                    View card = grid.getChildAt(0);
                    assertEquals(3, grid.getChildCount());
                    for (int index = 0; index < grid.getChildCount(); index++) {
                        assertEquals(Math.round(128 * density), grid.getChildAt(index).getWidth());
                        assertEquals(Math.round(160 * density), grid.getChildAt(index).getHeight());
                    }
                    assertEquals(Math.round(52 * density), card.findViewById(R.id.desktop_cover).getHeight());
                    TextView title = card.findViewById(R.id.desktop_name);
                    TextView badge = card.findViewById(R.id.desktop_mode_badge);
                    assertTrue(title.getTop() >= badge.getBottom());
                    assertTrue(title.getLineCount() <= 2);
                    TextView primary = card.findViewById(R.id.desktop_primary);
                    View more = card.findViewById(R.id.desktop_more);
                    assertTrue(primary.getRight() <= more.getLeft());
                    assertTrue(primary.getHeight() >= 48 * density);
                    assertTrue("font=" + scale + " height=" + primary.getHeight()
                        + " padding=" + primary.getCompoundPaddingTop() + "," + primary.getCompoundPaddingBottom()
                        + " text=" + primary.getLayout().getHeight() + " size=" + primary.getTextSize(),
                        primary.getHeight() - primary.getCompoundPaddingTop()
                        - primary.getCompoundPaddingBottom() >= primary.getLayout().getHeight());
                    assertTrue(more.getRight() <= ((View)more.getParent()).getWidth());
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    primary.getDrawingRect(bounds);
                    ((android.view.ViewGroup)card).offsetDescendantRectToMyCoords(primary, bounds);
                    assertTrue(bounds.top >= 0);
                    assertTrue(bounds.bottom <= card.getHeight());
                }
            }
        }
    }

    @Test public void cancelNeverEntersAndRepeatedConfirmationEntersOnlyOnce() throws Exception {
        verifyConfirmationMatrix();
    }

    @Test @Config(qualifiers = "w360dp-h640dp-port-night")
    public void confirmationAndBodyClicksMatchInDarkTheme() throws Exception {
        verifyConfirmationMatrix();
    }

    private void verifyConfirmationMatrix() throws Exception {
        for (DesktopRunMode mode : DesktopRunMode.values()) {
            for (FileManagerViewMode layout : FileManagerViewMode.values()) {
                android.content.Context context = RuntimeEnvironment.getApplication();
                layout.save(context, "desktop");
                context.getSharedPreferences("local_games_settings", 0).edit()
                    .putBoolean("desktop_proot", mode == DesktopRunMode.PROOT).commit();
                try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
                    DesktopActivity activity = controller.create().get();
                    // Keep real task polling out of this navigation-only test.
                    Field resumed = DesktopActivity.class.getDeclaredField("resumed");
                    resumed.setAccessible(true); resumed.set(activity, true);
                    DesktopDistro desktop = new DesktopDistro("confirmation", "My desktop", mode,
                        mode == DesktopRunMode.PROOT ? "ubuntu-xfce4-1" : "", DesktopDe.XFCE4,
                        DesktopGlMode.SYSTEM, mode == DesktopRunMode.PROOT ? DesktopGuestGlMode.DEFAULT
                            : DesktopGuestGlMode.NONE, "1280x720", 60, DesktopUsbStorageMode.OFF);
                    render(activity, desktop, DesktopSessionState.INSTALLED);
                    GridLayout grid = activity.findViewById(R.id.desktop_items);
                    assertFalse(grid.getChildAt(0).performClick());
                    confirm(activity, desktop, false);
                    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                    assertTrue(dialog.isShowing());
                    String message = ((TextView)dialog.findViewById(android.R.id.message)).getText().toString();
                    assertTrue(message.contains("My desktop"));
                    assertTrue(message.contains(activity.getString(mode == DesktopRunMode.PROOT
                        ? R.string.desktop_mode_proot_badge : R.string.desktop_mode_native)));
                    confirm(activity, desktop, false);
                    assertSame(dialog, ShadowDialog.getLatestDialog());
                    assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                    dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
                    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                    assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                    confirm(activity, desktop, true);
                    dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                    dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
                    Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                    Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
                    assertNotNull(intent);
                    assertEquals(DesktopSessionActivity.class.getName(), intent.getComponent().getClassName());
                    confirm(activity, desktop, true);
                    assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                }
            }
        }
    }
}
