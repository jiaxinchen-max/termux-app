package com.termux.ui.activity;

import android.Manifest;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Environment;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import com.google.android.material.appbar.MaterialToolbar;
import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Exercises the visible Up control, rather than invoking the parent-navigation methods. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w840dp-h660dp-land-notnight")
@LooperMode(LooperMode.Mode.PAUSED)
public class ToolbarUpNavigationTest {
    private Object previousFactory;

    @Before public void installHost() throws Exception {
        previousFactory = field(LocalGames.class, null, "hostFactory");
        LocalGames.install(context -> new LocalGamesHost() {
            @Override public boolean isRuntimeAvailable() { return false; }
        });
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE);
    }

    @After public void restoreHost() throws Exception {
        Field factory = LocalGames.class.getDeclaredField("hostFactory");
        factory.setAccessible(true);
        factory.set(null, previousFactory);
    }

    @Test public void settingsToolbarReturnsOneLevelForBothBackends() throws Exception {
        checkSettingsUpNavigation();
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port-night")
    public void settingsUpSurvivesRecreationAndRotationInDarkPortrait() throws Exception {
        checkSettingsUpNavigation();
    }

    private void checkSettingsUpNavigation() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            Intent intent = LocalGames.createCoreComponentsIntent(RuntimeEnvironment.getApplication(), backend);
            try (ActivityController<LocalGamesActivity> controller = Robolectric.buildActivity(
                    LocalGamesActivity.class, intent).setup().visible()) {
                LocalGamesActivity activity = controller.get();
                flushSettings(activity);
                assertUp(activity.findViewById(R.id.local_games_toolbar));
                controller.recreate();
                activity = controller.get();
                flushSettings(activity);
                Configuration rotated = new Configuration(activity.getResources().getConfiguration());
                rotated.orientation = rotated.orientation == Configuration.ORIENTATION_LANDSCAPE
                    ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
                activity.onConfigurationChanged(rotated);
                MaterialToolbar toolbar = activity.findViewById(R.id.local_games_toolbar);
                clickUp(toolbar);
                assertEquals("CORE_COMPONENTS", field(activity.getClass(), activity, "settingsPage").toString());
                clickUp(toolbar);
                assertEquals("ROOT", field(activity.getClass(), activity, "settingsPage").toString());
                assertNull(toolbar.getNavigationIcon());
                assertFalse(activity.isFinishing());
                // Opening the subpage from the root restores its arrow again.
                clickText(activity.findViewById(R.id.local_games_settings_menu),
                    activity.getString(R.string.local_games_settings_core_components));
                assertUp(toolbar);
                clickUp(toolbar);
                assertNull(toolbar.getNavigationIcon());
                assertNull(((MaterialToolbar) activity.findViewById(R.id.local_games_library_toolbar))
                    .getNavigationIcon());
            }
        }
    }

    @Test public void browserUpStopsAtEachOfTheThreeRoots() throws Exception {
        try (ActivityController<GameFileManagerActivity> controller = Robolectric.buildActivity(
                GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity activity = controller.get();
            File[] roots = {
                new GameStoragePaths(activity.getFilesDir()).getImportedGamesDirectory(),
                activity.getFilesDir(), Environment.getExternalStorageDirectory()
            };
            int[] labels = {R.string.local_game_file_manager_game_files,
                R.string.local_game_file_manager_private_files, R.string.local_game_file_manager_shared_storage};
            MaterialToolbar toolbar = activity.findViewById(R.id.local_game_file_manager_toolbar);
            assertNull(toolbar.getNavigationIcon());
            for (int index = 0; index < roots.length; index++) {
                File parent = new File(roots[index], "up-navigation-test");
                File child = new File(parent, "child");
                assertTrue(child.mkdirs());
                clickText(activity.findViewById(R.id.local_game_file_manager_items), activity.getString(labels[index]));
                assertDirectory(activity, roots[index]);
                clickText(activity.findViewById(R.id.local_game_file_manager_items), parent.getName());
                clickText(activity.findViewById(R.id.local_game_file_manager_items), child.getName());
                assertDirectory(activity, child);
                clickUp(toolbar);
                assertDirectory(activity, parent);
                clickUp(toolbar);
                assertDirectory(activity, roots[index]);
                clickUp(toolbar);
                assertNull(field(activity.getClass(), activity, "localCurrent"));
                assertNull(toolbar.getNavigationIcon());
                assertFalse(activity.isFinishing());
            }
        }
    }

    @Test public void browserRestoresUpAfterRecreationAndToolbarRefresh() throws Exception {
        checkBrowserRestoration();
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port-night")
    public void browserRestoresUpInDarkPortrait() throws Exception {
        checkBrowserRestoration();
    }

    private void checkBrowserRestoration() throws Exception {
        try (ActivityController<GameFileManagerActivity> controller = Robolectric.buildActivity(
                GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity activity = controller.get();
            clickText(activity.findViewById(R.id.local_game_file_manager_items),
                activity.getString(R.string.local_game_file_manager_game_files));
            File root = new GameStoragePaths(activity.getFilesDir()).getImportedGamesDirectory();
            controller.recreate();
            activity = controller.get();
            assertDirectory(activity, root);
            Configuration rotated = new Configuration(activity.getResources().getConfiguration());
            rotated.orientation = rotated.orientation == Configuration.ORIENTATION_LANDSCAPE
                ? Configuration.ORIENTATION_PORTRAIT : Configuration.ORIENTATION_LANDSCAPE;
            activity.onConfigurationChanged(rotated);
            MaterialToolbar toolbar = activity.findViewById(R.id.local_game_file_manager_toolbar);
            assertTrue(toolbar.getMenu().performIdentifierAction(R.id.local_games_action_view_mode, 0));
            assertTrue(toolbar.getMenu().performIdentifierAction(R.id.browser_show_hidden, 0));
            assertDirectory(activity, root);
            assertTrue(toolbar.getMenu().findItem(R.id.browser_new_file).isEnabled());
            assertTrue(toolbar.getMenu().findItem(R.id.browser_new_folder).isEnabled());
            clickUp(toolbar);
            assertNull(toolbar.getNavigationIcon());
        }
    }

    private static void assertDirectory(GameFileManagerActivity activity, File expected) throws Exception {
        assertEquals(expected.getCanonicalFile(), field(activity.getClass(), activity, "localCurrent"));
        assertUp(activity.findViewById(R.id.local_game_file_manager_toolbar));
    }

    private static void assertUp(MaterialToolbar toolbar) {
        assertNotNull(toolbar.getNavigationIcon());
        assertEquals(Integer.valueOf(ContextCompat.getColor(toolbar.getContext(), R.color.local_games_on_surface)),
            toolbar.getNavigationIconTint());
        assertEquals(toolbar.getContext().getString(androidx.appcompat.R.string.abc_action_bar_up_description),
            toolbar.getNavigationContentDescription());
    }

    private static void clickUp(MaterialToolbar toolbar) {
        assertUp(toolbar);
        for (int index = 0; index < toolbar.getChildCount(); index++) {
            View child = toolbar.getChildAt(index);
            if (toolbar.getNavigationContentDescription().equals(child.getContentDescription())) {
                assertEquals(View.VISIBLE, child.getVisibility());
                assertTrue(child.performClick());
                return;
            }
        }
        fail("Up button is not attached to the toolbar");
    }

    private static void clickText(View view, String label) {
        View match = findText(view, label);
        assertNotNull("Missing row " + label, match);
        while (!match.hasOnClickListeners() && match.getParent() instanceof View) {
            match = (View) match.getParent();
        }
        assertTrue("Row did not handle click: " + label, match.performClick());
    }

    private static View findText(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findText(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Object field(Class<?> type, Object instance, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void flushSettings(LocalGamesActivity activity) throws Exception {
        ExecutorService executor = (ExecutorService) field(activity.getClass(), activity, "libraryExecutor");
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
}
