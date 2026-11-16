package com.termux.ui.desktop.activity;

import android.content.Context;
import android.view.View;
import android.widget.GridLayout;
import android.widget.EditText;
import com.termux.ui.desktop.domain.*;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import android.widget.TextView;
import com.termux.ui.R;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.service.LaunchScriptInstaller;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DesktopCreateRenderingTest {
    @Test public void creationShowsSupportedDesktopCardsAndExplicitRendererLayers() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActivityController<DesktopCreateActivity> controller = Robolectric.buildActivity(
                DesktopCreateActivity.class, DesktopCreateActivity.createIntent(context, DesktopRunMode.PROOT))) {
            DesktopCreateActivity activity = controller.create().get();
            Method populate = DesktopCreateActivity.class.getDeclaredMethod("populate");
            populate.setAccessible(true); populate.invoke(activity);
            assertEquals(View.GONE, activity.findViewById(R.id.desktop_create_containers_section).getVisibility());
            GridLayout cards = activity.findViewById(R.id.desktop_create_des);
            assertEquals(4, cards.getChildCount());
            for (int i=0; i<cards.getChildCount(); i++) {
                assertEquals(View.GONE, cards.getChildAt(i).findViewById(R.id.desktop_option_soon).getVisibility());
            }
            View host = activity.findViewById(R.id.desktop_create_host_renderer);
            View guest = activity.findViewById(R.id.desktop_create_renderer);
            assertEquals(activity.getString(R.string.desktop_gl_system),
                ((TextView)host.findViewById(R.id.desktop_select_value)).getText().toString());
            assertEquals(activity.getString(R.string.desktop_guest_default),
                ((TextView)guest.findViewById(R.id.desktop_select_value)).getText().toString());
            assertFalse(guest.isEnabled());
            cards.getChildAt(1).performClick();
            assertEquals(activity.getString(R.string.desktop_gl_system),
                ((TextView)host.findViewById(R.id.desktop_select_value)).getText().toString());
            assertEquals(activity.getString(R.string.desktop_gl_llvmpipe),
                ((TextView)guest.findViewById(R.id.desktop_select_value)).getText().toString());
            cards.getChildAt(2).performClick();
            assertEquals(activity.getString(R.string.desktop_gl_system),
                ((TextView)host.findViewById(R.id.desktop_select_value)).getText().toString());
            assertEquals(activity.getString(R.string.desktop_gl_llvmpipe),
                ((TextView)guest.findViewById(R.id.desktop_select_value)).getText().toString());
            assertTrue(((TextView)activity.findViewById(R.id.desktop_create_alias)).getText().toString()
                .contains("ubuntu-ubuntu-gnome-1"));
            assertEquals(activity.getString(R.string.desktop_ubuntu_gnome_renderer_hint),
                ((TextView)activity.findViewById(R.id.desktop_create_note)).getText().toString());
        }
    }

    @Test public void nativeUbuntuHasFourCardsAndItsOwnStyleHint() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActivityController<DesktopCreateActivity> controller = Robolectric.buildActivity(
                DesktopCreateActivity.class, DesktopCreateActivity.createIntent(context, DesktopRunMode.NATIVE))) {
            DesktopCreateActivity activity = controller.create().get();
            Method populate = DesktopCreateActivity.class.getDeclaredMethod("populate");
            populate.setAccessible(true); populate.invoke(activity);
            GridLayout cards = activity.findViewById(R.id.desktop_create_des);
            assertEquals(4, cards.getChildCount());
            cards.getChildAt(2).performClick();
            assertEquals(activity.getString(R.string.desktop_native_ubuntu_hint),
                ((TextView)activity.findViewById(R.id.desktop_create_note)).getText().toString());
            assertEquals(activity.getString(R.string.desktop_guest_none),
                ((TextView)activity.findViewById(R.id.desktop_create_renderer)
                    .findViewById(R.id.desktop_select_value)).getText().toString());
        }
    }

    private static void populate(DesktopCreateActivity activity) throws Exception {
        Method method = DesktopCreateActivity.class.getDeclaredMethod("populate");
        method.setAccessible(true);
        method.invoke(activity);
    }

    private static void existing(DesktopCreateActivity activity, List<DesktopDistro> entries) throws Exception {
        Field field = DesktopCreateActivity.class.getDeclaredField("existing");
        field.setAccessible(true);
        field.set(activity, entries);
        populate(activity);
    }

    @Test public void nativeKeepsCreatedCardsAndSelectsFirstAvailableUntilFull() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActivityController<DesktopCreateActivity> controller = Robolectric.buildActivity(
                DesktopCreateActivity.class, DesktopCreateActivity.createIntent(context, DesktopRunMode.NATIVE))) {
            DesktopCreateActivity activity = controller.create().get();
            List<DesktopDistro> entries = new ArrayList<>();
            DesktopDe[] order = {DesktopDe.XFCE4, DesktopDe.KWIN_PLASMA, DesktopDe.UBUNTU_GNOME, DesktopDe.HYPRLAND};
            String[] titles = {"XFCE", "Plasma", "Ubuntu GNOME", "Hyprland"};
            for (int count = 0; count <= order.length; count++) {
                existing(activity, entries);
                GridLayout cards = activity.findViewById(R.id.desktop_create_des);
                assertEquals(4, cards.getChildCount());
                for (int i = 0; i < order.length; i++) {
                    View card = cards.getChildAt(i);
                    assertEquals(titles[i], ((TextView)card.findViewById(R.id.desktop_option_title)).getText().toString());
                    assertEquals(i >= count, card.isEnabled());
                    assertEquals(i == count, card.isSelected());
                    assertEquals(i < count ? View.VISIBLE : View.GONE,
                        card.findViewById(R.id.desktop_option_soon).getVisibility());
                }
                assertEquals(count < 4, activity.findViewById(R.id.desktop_create_action).isEnabled());
                if (count < order.length) entries.add(new DesktopDistro("desktop-" + count, titles[count],
                    DesktopRunMode.NATIVE, "", order[count], DesktopGlMode.SYSTEM,
                    DesktopGuestGlMode.NONE, "1280x720", 60, DesktopUsbStorageMode.OFF));
            }
            assertEquals(activity.getString(R.string.desktop_card_all_created),
                ((TextView)activity.findViewById(R.id.desktop_create_note)).getText().toString());
        }
    }

    @Test @Config(qualifiers = "night")
    public void prootAllowsRepeatsAndReflowRetainsSelectionAndEditedName() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActivityController<DesktopCreateActivity> controller = Robolectric.buildActivity(
                DesktopCreateActivity.class, DesktopCreateActivity.createIntent(context, DesktopRunMode.PROOT))) {
            DesktopCreateActivity activity = controller.create().get();
            List<DesktopDistro> entries = new ArrayList<>();
            entries.add(new DesktopDistro("existing", "Existing", DesktopRunMode.PROOT, "ubuntu-xfce4-1",
                DesktopDe.XFCE4, DesktopGlMode.SYSTEM, DesktopGuestGlMode.DEFAULT,
                "1280x720", 60, DesktopUsbStorageMode.OFF));
            existing(activity, entries);
            GridLayout cards = activity.findViewById(R.id.desktop_create_des);
            for (int i=0; i<4; i++) assertTrue(cards.getChildAt(i).isEnabled());
            assertEquals("XFCE #2", ((EditText)activity.findViewById(R.id.desktop_create_name)).getText().toString());
            cards.getChildAt(2).performClick();
            EditText name = activity.findViewById(R.id.desktop_create_name);
            name.setText("My Ubuntu desktop");
            populate(activity);
            assertTrue(cards.getChildAt(2).isSelected());
            assertEquals("My Ubuntu desktop", name.getText().toString());
        }
    }

    @Test public void recreationRetainsSelectedDesktopAndEditedName() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        android.os.Bundle state = new android.os.Bundle();
        try (ActivityController<DesktopCreateActivity> controller = Robolectric.buildActivity(
                DesktopCreateActivity.class, DesktopCreateActivity.createIntent(context, DesktopRunMode.PROOT))) {
            DesktopCreateActivity activity = controller.create().get();
            populate(activity);
            GridLayout cards = activity.findViewById(R.id.desktop_create_des);
            cards.getChildAt(3).performClick();
            ((EditText)activity.findViewById(R.id.desktop_create_name)).setText("My Hyprland");
            activity.onSaveInstanceState(state);
        }
        try (ActivityController<DesktopCreateActivity> controller = Robolectric.buildActivity(
                DesktopCreateActivity.class, DesktopCreateActivity.createIntent(context, DesktopRunMode.PROOT))) {
            DesktopCreateActivity activity = controller.create(state).get();
            populate(activity);
            GridLayout cards = activity.findViewById(R.id.desktop_create_des);
            assertTrue(cards.getChildAt(3).isSelected());
            assertEquals("My Hyprland", ((EditText)activity.findViewById(R.id.desktop_create_name)).getText().toString());
        }
    }

    @Test public void desktopScriptsDeployTheRequiredPairValidatorAndRepairIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        LaunchScriptInstaller installer = new LaunchScriptInstaller(context, paths);
        installer.installDesktopScripts();
        assertTrue(new File(paths.getRuntimeDirectory(), "desktop_ubuntu_gnome.py").isFile());
        assertTrue(new File(paths.getRuntimeDirectory(), "desktop_native_ubuntu.py").isFile());
        assertFalse(new File(paths.getRuntimeDirectory(), "native-ubuntu-gnome-48-r1.bin").exists());
        File helper = new File(paths.getRuntimeDirectory(), "desktop_rendering.sh");
        byte[] original = Files.readAllBytes(helper.toPath());
        assertTrue(new String(original, "UTF-8").contains("desktop_resolve_rendering()"));
        Files.write(helper.toPath(), new byte[]{0});
        installer.installDesktopScripts();
        assertArrayEquals(original, Files.readAllBytes(helper.toPath()));
    }
}
