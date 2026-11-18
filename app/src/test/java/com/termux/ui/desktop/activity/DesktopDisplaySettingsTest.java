package com.termux.ui.desktop.activity;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.termux.ui.R;
import com.termux.ui.desktop.api.DesktopTasks;
import com.termux.ui.desktop.domain.*;
import com.termux.ui.desktop.runtime.DesktopRendererPolicy;
import java.lang.reflect.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DesktopDisplaySettingsTest {
    @After public void dismiss() {
        if (ShadowDialog.getLatestDialog() != null) ShadowDialog.getLatestDialog().dismiss();
    }
    private static void set(Object object, String field, Object value) throws Exception {
        Field f = object.getClass().getDeclaredField(field); f.setAccessible(true); f.set(object, value);
    }
    private static void call(Object object, String method) throws Exception {
        Method m = object.getClass().getDeclaredMethod(method); m.setAccessible(true); m.invoke(object);
    }
    private static List<String> options(View row) {
        row.performClick();
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        ListView list = dialog.getListView();
        List<String> labels = new ArrayList<>();
        for (int i=0; i<list.getCount(); i++) labels.add(String.valueOf(list.getAdapter().getItem(i)));
        return labels;
    }
    private static void choose(int index) {
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getListView().performItemClick(null, index, index);
    }
    private static String value(View row) {
        return ((TextView) row.findViewById(R.id.desktop_select_value)).getText().toString();
    }
    private static void verifyRefresh(View row, DesktopDe de) {
        if (de.isGnomeSession()) {
            assertFalse(row.isEnabled());
            assertEquals("60 Hz", value(row));
            assertEquals(View.GONE, row.findViewById(R.id.desktop_select_arrow).getVisibility());
            assertEquals(View.VISIBLE, row.findViewById(R.id.desktop_select_hint).getVisibility());
            assertTrue(((TextView) row.findViewById(R.id.desktop_select_hint)).getText().toString().contains("60 Hz"));
            Dialog before = ShadowDialog.getLatestDialog(); row.performClick();
            assertSame(before, ShadowDialog.getLatestDialog());
        } else {
            assertTrue(row.isEnabled());
            assertEquals(Arrays.asList("30 Hz", "60 Hz", "90 Hz", "120 Hz"), options(row));
            choose(2); assertEquals("90 Hz", value(row));
        }
    }
    @Test public void creationOffersNativeAndHonestRefreshForEveryModeAndDesktop() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        for (DesktopRunMode mode : DesktopRunMode.values()) {
            try (ActivityController<DesktopCreateActivity> controller = Robolectric.buildActivity(
                    DesktopCreateActivity.class, DesktopCreateActivity.createIntent(context, mode)).create()) {
                DesktopCreateActivity activity = controller.get();
                for (DesktopDe de : DesktopDe.values()) if (de.isCreatable()) {
                    set(activity, "existing", new ArrayList<DesktopDistro>()); set(activity, "de", de);
                    set(activity, "resolution", "1280x720"); set(activity, "refreshRate", 120); call(activity, "populate");
                    View resolution = activity.findViewById(R.id.desktop_create_resolution);
                    assertEquals(Arrays.asList(activity.getString(R.string.desktop_resolution_native),
                        "1280x720", "1600x900", "1920x1080", "2560x1440"), options(resolution));
                    choose(0); assertEquals(activity.getString(R.string.desktop_resolution_native), value(resolution));
                    assertEquals(View.VISIBLE, resolution.findViewById(R.id.desktop_select_hint).getVisibility());
                    verifyRefresh(activity.findViewById(R.id.desktop_create_refresh), de);
                    assertEquals(DesktopRendererPolicy.hostModes(mode, de).size() > 1,
                        activity.findViewById(R.id.desktop_create_host_renderer).isEnabled());
                    assertEquals(DesktopRendererPolicy.guestModes(mode, de).size() > 1,
                        activity.findViewById(R.id.desktop_create_renderer).isEnabled());
                }
                // Repainting GNOME must not overwrite the user's preference for other desktops.
                set(activity,"de",DesktopDe.UBUNTU_GNOME);set(activity,"refreshRate",120);call(activity,"populate");
                set(activity,"de",DesktopDe.HYPRLAND);call(activity,"populate");
                assertEquals("120 Hz", value(activity.findViewById(R.id.desktop_create_refresh)));
            }
        }
    }

    @Test public void settingsUseTheSameNativeChoicesAndRefreshBehavior() throws Exception {
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class).create()) {
            DesktopActivity activity = controller.get();
            for (DesktopRunMode mode : DesktopRunMode.values()) for (DesktopDe de : DesktopDe.values()) {
                if (!de.isCreatable()) continue;
                DesktopDistro desktop = new DesktopDistro("settings-output", "Desktop", mode,
                    mode == DesktopRunMode.PROOT ? "output-1" : "", de, DesktopGlMode.SYSTEM,
                    mode == DesktopRunMode.NATIVE ? DesktopGuestGlMode.NONE
                        : de == DesktopDe.KWIN_PLASMA || de.isGnomeSession() ? DesktopGuestGlMode.LLVMPIPE : DesktopGuestGlMode.DEFAULT,
                    "native", de.isGnomeSession() ? 60 : 120, DesktopUsbStorageMode.OFF);
                DesktopSettingsDialog.show(activity, new DesktopTasks(activity), desktop, () -> { });
                Dialog sheet = ShadowDialog.getLatestDialog();
                View panel = sheet.findViewById(com.google.android.material.R.id.design_bottom_sheet);
                android.graphics.drawable.GradientDrawable background =
                    (android.graphics.drawable.GradientDrawable) panel.getBackground();
                assertEquals(activity.getColor(R.color.local_games_dialog_surface), background.getColor().getDefaultColor());
                assertEquals(217, android.graphics.Color.alpha(background.getColor().getDefaultColor()));
                assertEquals(1f, panel.getAlpha(), 0f);
                assertNull(((android.view.ViewGroup)panel).getChildAt(0).getBackground());
                View resolution = sheet.findViewById(R.id.desktop_settings_resolution);
                assertEquals(activity.getString(R.string.desktop_resolution_native), value(resolution));
                assertEquals(Arrays.asList(activity.getString(R.string.desktop_resolution_native),
                    "1280x720", "1600x900", "1920x1080", "2560x1440"), options(resolution));
                choose(3); assertEquals("1920x1080", value(resolution));
                verifyRefresh(sheet.findViewById(R.id.desktop_settings_refresh), de);
                assertEquals(DesktopRendererPolicy.hostModes(mode, de).size() > 1,
                    sheet.findViewById(R.id.desktop_settings_host_renderer).isEnabled());
                assertEquals(DesktopRendererPolicy.guestModes(mode, de).size() > 1,
                    sheet.findViewById(R.id.desktop_settings_renderer).isEnabled());
                sheet.dismiss();
            }
        }
    }
}
