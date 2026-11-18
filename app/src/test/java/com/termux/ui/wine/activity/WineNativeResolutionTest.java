package com.termux.ui.wine.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import androidx.appcompat.app.AppCompatActivity;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.databinding.ActivityGameLaunchBinding;
import com.termux.ui.wine.data.*;
import com.termux.ui.wine.domain.*;
import com.termux.x11.X11SessionView;
import java.io.File;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="w840dp-h660dp-land", shadows=WineNativeResolutionTest.DisplayShadow.class)
public class WineNativeResolutionTest {
    private static String bound;
    @Implements(X11SessionView.class)
    public static class DisplayShadow extends org.robolectric.shadows.ShadowViewGroup {
        @Implementation protected void initialize(Context context) { }
        @Implementation protected void bind(AppCompatActivity activity, String resolution, X11SessionView.Listener listener) { bound = resolution; }
        @Implementation protected void setInputApis(boolean xinput, boolean dinput) { }
        @Implementation protected boolean applyInputProfile(String selection) { return true; }
        @Implementation protected void unbind() { }
    }

    private GameStoragePaths prepare(String id) throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        File gameRoot = new File(context.getFilesDir(), "native-window-game");
        assertTrue(gameRoot.mkdirs()); assertTrue(new File(gameRoot, "game.exe").createNewFile());
        new FileGameRepository(paths.getLibraryDirectory()).save(new Game(id, "Window", gameRoot.toURI().toString(),
            "game.exe", ".", Collections.emptyList(), "", 0));
        new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(
            RuntimeProfilePresets.create(id, RuntimeProfilePreset.RECOMMENDED).withResolution("native"));
        return paths;
    }

    private void flush(GameLaunchActivity activity) throws Exception {
        for (int i=0; i<3; i++) {
            ((ExecutorService)ReflectionHelpers.getField(activity, "ioExecutor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test public void measuresWindowPixelsBeforeQueueingAndKeepsNativeSaved() throws Exception {
        GameStoragePaths paths = prepare("window");
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), GameLaunchActivity.class)
            .putExtra(GameLaunchActivity.EXTRA_GAME_ID, "window");
        try (ActivityController<GameLaunchActivity> c = Robolectric.buildActivity(GameLaunchActivity.class, intent).create().start().resume()) {
            GameLaunchActivity activity = c.get(); flush(activity);
            assertTrue(new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).list().isEmpty());
            ActivityGameLaunchBinding binding = ReflectionHelpers.getField(activity, "binding");
            View root = binding.getRoot();
            root.measure(View.MeasureSpec.makeMeasureSpec(1440, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 1440, 900);
            root.getViewTreeObserver().dispatchOnPreDraw(); flush(activity);
            LaunchTask task = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).list().get(0);
            assertEquals("1440x900", new LaunchWindowGeometry(paths.getLaunchSpecsDirectory()).read(task.getTaskId()));
            assertEquals("native", new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find("window").get().getResolution());
        }
    }

    @Test public void reenteringActiveTaskDoesNotReplaceItsOriginalWindowRequest() throws Exception {
        GameStoragePaths paths = prepare("window");
        new LaunchWindowGeometry(paths.getLaunchSpecsDirectory()).write("launch-existing", 1600, 900);
        new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).save(LaunchTask.queued("launch-existing", "window", 100));
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), GameLaunchActivity.class)
            .putExtra(GameLaunchActivity.EXTRA_GAME_ID, "window");
        try (ActivityController<GameLaunchActivity> c = Robolectric.buildActivity(GameLaunchActivity.class, intent).setup().visible()) {
            flush(c.get());
            assertEquals("launch-existing", ReflectionHelpers.getField(c.get(), "taskId"));
            assertEquals(1, new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).list().size());
            assertEquals("1600x900", new LaunchWindowGeometry(paths.getLaunchSpecsDirectory()).read("launch-existing"));
        }
    }

    @Test public void sessionBindsFrozenPixelsDespiteLaterNativeProfileAndWindowChanges() throws Exception {
        GameStoragePaths paths = prepare("window");
        LaunchSpec spec = new LaunchSpec("launch-frozen", "window", "/games", "game.exe", ".", Collections.emptyList(),
            "/prefix", "glibc_wine_9.3-vanilla", "virgl", "wined3d", "alsa", "1600x900", "INTERMEDIATE",
            "xinput", Collections.emptyMap(), "/events", "/log", "/lock", "/cancel", 300);
        new LaunchSpecCodec().write(new File(paths.getLaunchSpecsDirectory(), "launch-frozen.launchspec"), spec);
        new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).save(LaunchTask.queued("launch-frozen", "window", 100));
        bound = null;
        try (ActivityController<GameSessionActivity> c = Robolectric.buildActivity(GameSessionActivity.class,
            GameSessionActivity.createIntent(RuntimeEnvironment.getApplication(), "launch-frozen")).setup().visible()) {
            assertEquals("1600x900", bound);
            c.recreate();
            assertEquals("1600x900", bound);
        }
    }
}
