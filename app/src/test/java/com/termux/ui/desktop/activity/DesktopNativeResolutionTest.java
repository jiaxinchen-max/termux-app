package com.termux.ui.desktop.activity;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.termux.ui.R;
import com.termux.ui.api.*;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import com.termux.ui.desktop.domain.*;
import com.termux.x11.X11SessionView;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, shadows=DesktopNativeResolutionTest.DisplayShadow.class)
public class DesktopNativeResolutionTest {
    private static String boundResolution;
    private Object oldFactory;
    private volatile Properties launchSpec;

    @Implements(X11SessionView.class)
    public static class DisplayShadow extends org.robolectric.shadows.ShadowViewGroup {
        @Implementation protected void initialize(Context context) { }
        @Implementation protected void bind(AppCompatActivity activity, String resolution, int hz, X11SessionView.Listener listener) {
            boundResolution = resolution;
        }
        @Implementation protected void unbind() { }
    }

    @Before public void setup() throws Exception {
        Field factory = LocalGames.class.getDeclaredField("hostFactory"); factory.setAccessible(true); oldFactory = factory.get(null);
        boundResolution = null; launchSpec = null;
        LocalGames.install(context -> new LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public void prepareLaunchRuntime() { fail("resolving display must not prepare runtime"); }
            public void startLaunch(LaunchRequest request) {
                if (!request.getTaskId().startsWith("desktop-start-")) return;
                Properties spec = new Properties();
                try (FileInputStream input = new FileInputStream(new GameStoragePaths(context.getFilesDir()).getDesktopSpecFile("native-output"))) {
                    spec.load(input);
                } catch (Exception error) { throw new AssertionError(error); }
                launchSpec = spec;
            }
        });
    }
    @After public void cleanup() throws Exception {
        Field factory = LocalGames.class.getDeclaredField("hostFactory"); factory.setAccessible(true); factory.set(null, oldFactory);
    }

    @Test public void nativeViewAndBackendUseTheSameMeasuredPixelsOnlyAfterLayout() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        DesktopDistro desktop = new DesktopDistro("native-output", "Output", DesktopRunMode.NATIVE, "",
            DesktopDe.XFCE4, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "native", 120, DesktopUsbStorageMode.OFF);
        new FileDesktopDistroRepository(new GameStoragePaths(context.getFilesDir()).getDesktopDistrosDirectory()).save(desktop);
        try (ActivityController<DesktopSessionActivity> controller = Robolectric.buildActivity(DesktopSessionActivity.class,
                DesktopSessionActivity.createIntent(context, desktop.getId())).create().start().resume()) {
            assertNull(boundResolution); assertNull(launchSpec);
            View display = controller.get().findViewById(R.id.desktop_session_x11);
            display.layout(0, 0, 2400, 1080);
            display.getViewTreeObserver().dispatchOnPreDraw();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (launchSpec == null && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20);
            }
            assertNotNull("The measured view must submit a launch", launchSpec);
            assertEquals("2400x1080", boundResolution);
            assertEquals("2400", launchSpec.getProperty("DESKTOP_WIDTH"));
            assertEquals("1080", launchSpec.getProperty("DESKTOP_HEIGHT"));
            assertEquals("native", launchSpec.getProperty("DESKTOP_RESOLUTION"));
            assertEquals("120", launchSpec.getProperty("DESKTOP_REFRESH_RATE"));
            display.layout(0, 0, 1080, 2400); display.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("2400x1080", boundResolution);
        }
    }
}
