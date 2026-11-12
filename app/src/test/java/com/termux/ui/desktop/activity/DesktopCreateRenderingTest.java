package com.termux.ui.desktop.activity;

import android.content.Context;
import android.view.View;
import android.widget.LinearLayout;
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
            LinearLayout cards = activity.findViewById(R.id.desktop_create_des);
            assertEquals(5, cards.getChildCount());
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
            cards.getChildAt(2).performClick();
            assertEquals(activity.getString(R.string.desktop_gl_system),
                ((TextView)host.findViewById(R.id.desktop_select_value)).getText().toString());
            assertEquals(activity.getString(R.string.desktop_gl_llvmpipe),
                ((TextView)guest.findViewById(R.id.desktop_select_value)).getText().toString());
            cards.getChildAt(4).performClick();
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

    @Test public void nativeUbuntuHasFiveCardsAndItsOwnStyleHint() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        try (ActivityController<DesktopCreateActivity> controller = Robolectric.buildActivity(
                DesktopCreateActivity.class, DesktopCreateActivity.createIntent(context, DesktopRunMode.NATIVE))) {
            DesktopCreateActivity activity = controller.create().get();
            Method populate = DesktopCreateActivity.class.getDeclaredMethod("populate");
            populate.setAccessible(true); populate.invoke(activity);
            LinearLayout cards = activity.findViewById(R.id.desktop_create_des);
            assertEquals(5, cards.getChildCount());
            cards.getChildAt(4).performClick();
            assertEquals(activity.getString(R.string.desktop_native_ubuntu_hint),
                ((TextView)activity.findViewById(R.id.desktop_create_note)).getText().toString());
            assertEquals(activity.getString(R.string.desktop_guest_none),
                ((TextView)activity.findViewById(R.id.desktop_create_renderer)
                    .findViewById(R.id.desktop_select_value)).getText().toString());
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
