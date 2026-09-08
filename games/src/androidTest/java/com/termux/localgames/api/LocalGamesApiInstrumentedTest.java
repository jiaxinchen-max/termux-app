package com.termux.localgames.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.preference.PreferenceManager;
import android.view.KeyEvent;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.lifecycle.Lifecycle;

import com.termux.localgames.activity.LocalGamesActivity;
import com.termux.localgames.activity.GameImportActivity;
import com.termux.localgames.activity.GameDetailActivity;
import com.termux.localgames.activity.GameRuntimeProfileActivity;
import com.termux.localgames.activity.GameAssetsActivity;
import com.termux.localgames.activity.GameLaunchActivity;
import com.termux.localgames.activity.GameSessionActivity;
import com.termux.localgames.artwork.GameArtworkLoader;
import com.termux.localgames.artwork.GameArtworkStore;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.data.FileLaunchTaskRepository;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTask;
import com.termux.localgames.domain.LaunchTaskState;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.recovery.GameUninstallPlan;
import com.termux.localgames.recovery.GameUninstaller;
import com.termux.localgames.importer.SafPermissionManager;
import com.termux.localgames.service.ComponentTaskForegroundService;
import com.termux.localgames.service.LocalGameOrchestratorService;
import com.termux.x11.X11SessionView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class LocalGamesApiInstrumentedTest {

    @Test
    public void createLaunchIntentTargetsModuleActivity() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = LocalGames.createLaunchIntent(context);

        assertNotNull(intent.getComponent());
        assertEquals(LocalGamesActivity.class.getName(), intent.getComponent().getClassName());
    }

    @Test
    public void componentReconcileTargetsPrivateForegroundService() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = ComponentTasks.createReconcileIntent(context);

        assertEquals(ComponentTasks.ACTION_RECONCILE, intent.getAction());
        assertEquals(ComponentTaskForegroundService.class.getName(),
            intent.getComponent().getClassName());
        assertFalse(context.getPackageManager().getServiceInfo(intent.getComponent(), 0).exported);
    }

    @Test
    public void runtimeProvisionReconcileAllTargetsPrivateForegroundService() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = RuntimeProvisionTasks.createReconcileAllIntent(context);

        assertEquals(RuntimeProvisionTasks.ACTION_RECONCILE_ALL, intent.getAction());
        assertEquals(com.termux.localgames.service.RootfsProvisionForegroundService.class.getName(),
            intent.getComponent().getClassName());
        assertFalse(context.getPackageManager().getServiceInfo(intent.getComponent(), 0).exported);
    }

    @Test
    public void gameLibraryProvidesManualOrientationToggle() {
        try (ActivityScenario<LocalGamesActivity> scenario =
                 ActivityScenario.launch(LocalGamesActivity.class)) {
            scenario.onActivity(activity -> {
                boolean landscape = activity.getResources().getConfiguration().orientation ==
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE;
                View orientation = activity.findViewById(
                    com.termux.localgames.R.id.local_games_orientation_button);
                assertEquals(View.VISIBLE, orientation.getVisibility());
                orientation.performClick();
                assertEquals(landscape ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                    activity.getRequestedOrientation());
            });
        }
    }

    @Test
    public void importIntentTargetsPrivateActivityAndPickerRequestsPersistentReadOnlyTree() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent importIntent = LocalGames.createImportIntent(context);
        assertNotNull(importIntent.getComponent());
        assertEquals(GameImportActivity.class.getName(),
            importIntent.getComponent().getClassName());
        ActivityInfo info = context.getPackageManager().getActivityInfo(
            importIntent.getComponent(), 0);
        assertFalse(info.exported);

        Intent picker = SafPermissionManager.createOpenTreeIntent();
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.getAction());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
            picker.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            picker.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        assertEquals(0, picker.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    @Test
    public void detailIntentTargetsPrivateActivity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = LocalGames.createGameDetailIntent(context, "game-1");

        assertNotNull(intent.getComponent());
        assertEquals(GameDetailActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals("game-1", intent.getStringExtra(GameDetailActivity.EXTRA_GAME_ID));
        ActivityInfo info = context.getPackageManager().getActivityInfo(intent.getComponent(), 0);
        assertFalse(info.exported);
    }

    @Test
    public void runtimeProfileIntentTargetsPrivateActivity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = LocalGames.createRuntimeProfileIntent(context, "game-1");

        assertNotNull(intent.getComponent());
        assertEquals(GameRuntimeProfileActivity.class.getName(),
            intent.getComponent().getClassName());
        assertEquals("game-1", intent.getStringExtra(GameRuntimeProfileActivity.EXTRA_GAME_ID));
        ActivityInfo info = context.getPackageManager().getActivityInfo(intent.getComponent(), 0);
        assertFalse(info.exported);
    }

    @Test
    public void assetsIntentTargetsPrivateActivity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = LocalGames.createGameAssetsIntent(context, "game-1");

        assertEquals(GameAssetsActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals("game-1", intent.getStringExtra(GameAssetsActivity.EXTRA_GAME_ID));
        assertFalse(context.getPackageManager().getActivityInfo(intent.getComponent(), 0).exported);
    }

    @Test
    public void launchIntentAndOrchestratorServiceArePrivate() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent activityIntent = LocalGames.createGameLaunchIntent(context, "game-1");
        assertEquals(GameLaunchActivity.class.getName(),
            activityIntent.getComponent().getClassName());
        assertEquals("game-1", activityIntent.getStringExtra(GameLaunchActivity.EXTRA_GAME_ID));
        ActivityInfo activityInfo = context.getPackageManager().getActivityInfo(
            activityIntent.getComponent(), 0);
        assertFalse(activityInfo.exported);

        Intent sessionIntent = LocalGames.createGameSessionIntent(context, "task-1");
        assertEquals(GameSessionActivity.class.getName(),
            sessionIntent.getComponent().getClassName());
        ActivityInfo sessionInfo = context.getPackageManager().getActivityInfo(
            sessionIntent.getComponent(), 0);
        assertFalse(sessionInfo.exported);

        Intent serviceIntent = LaunchTasks.command(context, LaunchTasks.ACTION_RECONCILE, "task-1");
        assertEquals(LocalGameOrchestratorService.class.getName(),
            serviceIntent.getComponent().getClassName());
        ServiceInfo serviceInfo = context.getPackageManager().getServiceInfo(
            serviceIntent.getComponent(), 0);
        assertFalse(serviceInfo.exported);
        Intent frameIntent = LaunchTasks.command(context, LaunchTasks.ACTION_FIRST_FRAME, "task-1");
        assertEquals(LocalGameOrchestratorService.class.getName(),
            frameIntent.getComponent().getClassName());
    }

    @Test
    public void launchStatusRecreationObservesPersistedTaskWithoutRestart() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        FileLaunchTaskRepository repository = new FileLaunchTaskRepository(
            paths.getLaunchTasksDirectory());
        String taskId = "instrumented-launch-terminal";
        LaunchTask terminal = LaunchTask.queued(taskId, "game-1", System.currentTimeMillis())
            .transition(LaunchTaskState.SUCCEEDED, LaunchStage.COMPLETE, 100,
                123, 0, "", false, "logs/task.log");
        repository.save(terminal);

        try {
            try (ActivityScenario<GameLaunchActivity> scenario = ActivityScenario.launch(
                GameLaunchActivity.createTaskIntent(context, "game-1", taskId))) {
                assertTrue(waitForLaunchState(scenario,
                    com.termux.localgames.R.string.local_game_launch_phase_complete));
                scenario.recreate();
                assertTrue(waitForLaunchState(scenario,
                    com.termux.localgames.R.string.local_game_launch_phase_complete));
                assertEquals(1, repository.list().stream()
                    .filter(task -> task.getTaskId().equals(taskId)).count());
            }
        } finally {
            new File(paths.getLaunchTasksDirectory(), taskId + ".properties").delete();
        }
    }

    @Test
    public void gameSessionBackTogglesControlCenterAndExplicitExitCanBeCancelled() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        boolean terminalMouseHelper = PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("showMouseHelper", false);
        context.getSharedPreferences(X11SessionView.PREFERENCE_NAMESPACE, Context.MODE_PRIVATE)
            .edit().clear().commit();
        LocalGames.install(applicationContext -> new LocalGamesHost() {
            @Override public boolean isRuntimeAvailable() { return true; }
            @Override public boolean isProcessAlive(long pid) { return true; }
        });
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        FileLaunchTaskRepository repository = new FileLaunchTaskRepository(
            paths.getLaunchTasksDirectory());
        String taskId = "instrumented-x11-session";
        File taskFile = new File(paths.getLaunchTasksDirectory(), taskId + ".properties");
        File cancelFile = new File(paths.getLaunchCancelDirectory(), taskId + ".cancel");
        taskFile.delete();
        cancelFile.delete();
        repository.save(LaunchTask.queued(taskId, "game-1", System.currentTimeMillis())
            .transition(LaunchTaskState.RUNNING, LaunchStage.WAITING_FIRST_FRAME, 90,
                123, null, "", false, "logs/task.log"));
        try (ActivityScenario<GameSessionActivity> scenario = ActivityScenario.launch(
            LocalGames.createGameSessionIntent(context, taskId))) {
            scenario.moveToState(Lifecycle.State.CREATED);
            assertFalse(cancelFile.exists());
            scenario.moveToState(Lifecycle.State.RESUMED);
            scenario.onActivity(activity -> assertEquals(View.GONE,
                activity.findViewById(com.termux.x11.R.id.display_terminal_toolbar_view_pager)
                    .getVisibility()));
            scenario.recreate();
            assertFalse(cancelFile.exists());
            scenario.onActivity(LocalGamesApiInstrumentedTest::dispatchBackKey);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withId(com.termux.localgames.R.id.game_session_drawer)).check(matches(isDisplayed()));
            scenario.onActivity(activity -> {
                activity.findViewById(com.termux.localgames.R.id.game_session_category_performance)
                    .performClick();
                assertEquals(View.VISIBLE, activity.findViewById(
                    com.termux.localgames.R.id.game_session_process_summary).getVisibility());
                activity.findViewById(com.termux.localgames.R.id.game_session_category_settings)
                    .performClick();
                assertEquals(View.VISIBLE, activity.findViewById(
                    com.termux.localgames.R.id.game_session_brightness).getVisibility());
                ((android.widget.CompoundButton) activity.findViewById(
                    com.termux.localgames.R.id.game_session_mouse_helper)).setChecked(true);
                activity.findViewById(com.termux.localgames.R.id.game_session_category_keyboard)
                    .performClick();
                assertEquals(View.VISIBLE, activity.findViewById(
                    com.termux.localgames.R.id.game_session_keyboard).getVisibility());
            });
            scenario.onActivity(activity -> activity.getOnBackPressedDispatcher().onBackPressed());
            Thread.sleep(220);
            assertFalse(cancelFile.exists());
            scenario.onActivity(LocalGamesApiInstrumentedTest::dispatchBackKey);
            scenario.onActivity(activity -> activity.findViewById(
                com.termux.localgames.R.id.game_session_exit).performClick());
            Thread.sleep(220);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withText(android.R.string.cancel)).perform(click());
            assertFalse(cancelFile.exists());
            assertEquals(terminalMouseHelper,
                PreferenceManager.getDefaultSharedPreferences(context)
                    .getBoolean("showMouseHelper", false));
            assertTrue(context.getSharedPreferences(
                X11SessionView.PREFERENCE_NAMESPACE, Context.MODE_PRIVATE)
                .getBoolean("showMouseHelper", false));
        } finally {
            taskFile.delete();
            cancelFile.delete();
            context.getSharedPreferences(X11SessionView.PREFERENCE_NAMESPACE, Context.MODE_PRIVATE)
                .edit().clear().commit();
        }
    }

    private static void dispatchBackKey(GameSessionActivity activity) {
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
        activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
    }

    @Test
    public void lostSafPermissionRecoverySurvivesActivityRecreation() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String missingTree = "content://missing.documents/tree/game";
        Intent intent = LocalGames.createReauthorizeIntent(context, missingTree);

        try (ActivityScenario<GameImportActivity> scenario = ActivityScenario.launch(intent)) {
            assertLostPermissionState(scenario, missingTree);
            scenario.recreate();
            assertLostPermissionState(scenario, missingTree);
        }
    }

    @Test
    public void persistedGameAppearsWithPermissionStateAndDetailSurvivesRecreation()
        throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FileGameRepository repository = repository(context);
        String gameId = "instrumented-library-game";
        repository.delete(gameId);
        repository.save(game(gameId, "Instrumented Game", "content://missing/tree/library"));
        try {
            try (ActivityScenario<LocalGamesActivity> scenario =
                     ActivityScenario.launch(LocalGamesActivity.class)) {
                assertTrue(waitForLibraryGame(scenario, "Instrumented Game"));
                scenario.recreate();
                assertTrue(waitForLibraryGame(scenario, "Instrumented Game"));
            }
            try (ActivityScenario<GameDetailActivity> scenario = ActivityScenario.launch(
                LocalGames.createGameDetailIntent(context, gameId))) {
                assertTrue(waitForDetail(scenario, "Instrumented Game"));
                scenario.recreate();
                assertTrue(waitForDetail(scenario, "Instrumented Game"));
            }
        } finally {
            repository.delete(gameId);
        }
    }

    @Test
    public void privateArtworkReplacementRollbackAndRemovalAreTransactional() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String gameId = "instrumented-artwork-game";
        GameArtworkStore store = new GameArtworkStore(context.getFilesDir());
        store.delete(gameId);
        int originalColor = 0xff336699;
        byte[] png = pngBytes(originalColor);
        try {
            try (GameArtworkStore.Mutation mutation = store.stageReplacement(gameId,
                new ByteArrayInputStream(png))) {
                mutation.commit();
            }
            String reference = com.termux.localgames.artwork.GameArtworkReference.forGame(gameId);
            assertNotNull(new GameArtworkLoader(store).load(reference, 64));

            try (GameArtworkStore.Mutation ignored = store.stageReplacement(gameId,
                new ByteArrayInputStream(pngBytes(0xff993333)))) {
                // close without commit rolls the replacement back to the prior cover.
            }
            Bitmap rolledBack = new GameArtworkLoader(store).load(reference, 64);
            assertNotNull(rolledBack);
            assertEquals(originalColor, rolledBack.getPixel(0, 0));

            try {
                store.stageReplacement(gameId, new ByteArrayInputStream(new byte[] {1, 2, 3}));
                fail("Invalid image should be rejected");
            } catch (java.io.IOException expected) {
                assertNotNull(new GameArtworkLoader(store).load(reference, 64));
            }

            try (GameArtworkStore.Mutation ignored = store.stageRemoval(gameId)) {
                // close without commit restores the removed cover.
            }
            assertNotNull(new GameArtworkLoader(store).load(reference, 64));

            try (GameArtworkStore.Mutation mutation = store.stageRemoval(gameId)) {
                mutation.commit();
            }
            assertEquals(null, new GameArtworkLoader(store).load(reference, 64));
        } finally {
            store.delete(gameId);
        }
    }

    @Test
    public void runtimeProfileDefaultsUnsavedFormAndSaveSurviveRecreation() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String gameId = "instrumented-runtime-profile";
        FileGameRepository games = repository(context);
        FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(
            new GameStoragePaths(context.getFilesDir()).getProfilesDirectory());
        games.delete(gameId);
        profiles.delete(gameId);
        games.save(game(gameId, "Runtime Profile Test", "content://missing/tree/profile"));
        try (ActivityScenario<GameRuntimeProfileActivity> scenario = ActivityScenario.launch(
            LocalGames.createRuntimeProfileIntent(context, gameId))) {
            assertTrue(waitForRuntimeProfile(scenario,
                "Wine 9.3 Vanilla · 9.3 WoW64"));
            scenario.onActivity(activity -> {
                ((android.widget.AutoCompleteTextView) activity.findViewById(
                    com.termux.localgames.R.id.runtime_profile_wine)).setText(
                    "Wine 9.0 Staging · 9.0 WoW64", false);
                assertTrue(((android.widget.TextView) activity.findViewById(
                    com.termux.localgames.R.id.runtime_profile_changes)).getText()
                    .toString().contains("wine-9.0-staging-wow64"));
            });
            scenario.onActivity(activity -> ((android.widget.AutoCompleteTextView)
                activity.findViewById(com.termux.localgames.R.id.runtime_profile_execution_mode))
                .setText(activity.getString(com.termux.localgames.R.string
                    .local_game_runtime_profile_execution_terminal), false));
            scenario.recreate();
            assertTrue(waitForRuntimeProfile(scenario,
                "Wine 9.0 Staging · 9.0 WoW64"));
            assertRuntimeExecutionMode(scenario,
                com.termux.localgames.R.string.local_game_runtime_profile_execution_terminal);
            scenario.onActivity(activity -> activity.findViewById(
                com.termux.localgames.R.id.runtime_profile_save).performClick());
            assertTrue(waitForSavedProfile(profiles, gameId,
                "wine-9.0-staging-wow64"));
            assertEquals(LaunchExecutionMode.TERMINAL_SESSION,
                profiles.find(gameId).get().getLaunchExecutionMode());
            scenario.onActivity(activity -> activity.findViewById(
                com.termux.localgames.R.id.runtime_profile_restore_default).performClick());
            assertTrue(waitForRuntimeProfile(scenario,
                "Wine 9.3 Vanilla · 9.3 WoW64"));
        } finally {
            profiles.delete(gameId);
            games.delete(gameId);
        }
    }

    @Test
    public void detailDeleteRemovesOnlyPrivateRecordAndLeavesExternalMarker() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String gameId = "instrumented-delete-game";
        FileGameRepository repository = repository(context);
        File marker = new File(context.getExternalFilesDir(null), gameId + ".marker");
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        File privatePrefixMarker = new File(paths.getGamePrefixDirectory(gameId), "keep.marker");
        try (FileOutputStream output = new FileOutputStream(marker)) {
            output.write(1);
        }
        if (!privatePrefixMarker.getParentFile().isDirectory()) {
            assertTrue(privatePrefixMarker.getParentFile().mkdirs());
        }
        try (FileOutputStream output = new FileOutputStream(privatePrefixMarker)) {
            output.write(1);
        }
        repository.save(game(gameId, "Delete Test", marker.toURI().toString()));
        try (ActivityScenario<GameDetailActivity> scenario = ActivityScenario.launch(
            LocalGames.createGameDetailIntent(context, gameId))) {
            assertTrue(waitForDetail(scenario, "Delete Test"));
            scenario.onActivity(activity -> activity.findViewById(
                com.termux.localgames.R.id.game_detail_delete).performClick());
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            onView(withText(com.termux.localgames.R.string.local_game_detail_delete_confirm))
                .perform(click());
            assertTrue(waitForGameDeletion(repository, gameId));
            assertTrue(marker.isFile());
            assertTrue(privatePrefixMarker.isFile());
        } finally {
            repository.delete(gameId);
            marker.delete();
            new GameUninstaller(context.getFilesDir()).execute(
                GameUninstallPlan.keepPrivateAssets(gameId)
                    .withSelections(true, true, true, true, true));
        }
    }

    @Test
    public void assetsInventorySnapshotAndRecreationUsePrivateStorage() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String gameId = "instrumented-assets-game";
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        FileGameRepository repository = repository(context);
        repository.delete(gameId);
        repository.save(game(gameId, "Assets Test", "content://missing/tree/assets"));
        new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(
            RuntimeProfilePresets.create(gameId, RuntimeProfilePreset.RECOMMENDED));
        File prefixFile = new File(paths.getGamePrefixDirectory(gameId), "drive_c/save.dat");
        assertTrue(prefixFile.getParentFile().mkdirs() || prefixFile.getParentFile().isDirectory());
        try (FileOutputStream output = new FileOutputStream(prefixFile)) { output.write(1); }
        try (ActivityScenario<GameAssetsActivity> scenario = ActivityScenario.launch(
            LocalGames.createGameAssetsIntent(context, gameId))) {
            assertTrue(waitForAssetCategories(scenario));
            scenario.onActivity(activity -> activity.findViewById(
                com.termux.localgames.R.id.game_assets_create_snapshot).performClick());
            assertTrue(waitForSnapshotRows(scenario));
            scenario.recreate();
            assertTrue(waitForAssetCategories(scenario));
            assertTrue(waitForSnapshotRows(scenario));
        } finally {
            new GameUninstaller(context.getFilesDir()).execute(
                GameUninstallPlan.keepPrivateAssets(gameId)
                    .withSelections(true, true, true, true, true));
        }
    }

    @Test
    public void componentCommandTargetsPrivateModuleService() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

        Intent intent = ComponentTasks.createCommandIntent(context,
            ComponentTasks.ACTION_PAUSE, "task-1");

        assertNotNull(intent.getComponent());
        assertEquals(ComponentTaskForegroundService.class.getName(),
            intent.getComponent().getClassName());
        ServiceInfo info = context.getPackageManager().getServiceInfo(intent.getComponent(), 0);
        assertFalse(info.exported);
    }

    @Test
    public void componentCatalogSurvivesActivityRecreation() throws Exception {
        try (ActivityScenario<LocalGamesActivity> scenario =
                 ActivityScenario.launch(LocalGamesActivity.class)) {
            scenario.onActivity(activity -> activity.findViewById(
                com.termux.localgames.R.id.local_games_components_button).performClick());

            assertEquals(17, waitForComponentRows(scenario));
            assertCategoryCounts(scenario);
            scenario.recreate();

            scenario.onActivity(activity -> assertEquals(View.VISIBLE,
                activity.findViewById(com.termux.localgames.R.id.local_games_components_page)
                    .getVisibility()));
            assertEquals(17, waitForComponentRows(scenario));
            assertCategoryCounts(scenario);
        }
    }

    private static int waitForComponentRows(ActivityScenario<LocalGamesActivity> scenario)
        throws InterruptedException {
        AtomicInteger count = new AtomicInteger();
        for (int attempt = 0; attempt < 50; attempt++) {
            scenario.onActivity(activity -> count.set(componentRowCount(activity)));
            if (count.get() == 17) return count.get();
            Thread.sleep(50);
        }
        return count.get();
    }

    private static boolean waitForLaunchState(ActivityScenario<GameLaunchActivity> scenario,
                                              int expectedText) throws InterruptedException {
        AtomicBoolean matched = new AtomicBoolean();
        for (int attempt = 0; attempt < 50; attempt++) {
            scenario.onActivity(activity -> matched.set(activity.getString(expectedText).equals(
                ((android.widget.TextView) activity.findViewById(
                    com.termux.localgames.R.id.game_launch_state)).getText().toString())));
            if (matched.get()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static void assertCategoryCounts(ActivityScenario<LocalGamesActivity> scenario) {
        AtomicInteger sections = new AtomicInteger();
        AtomicInteger rows = new AtomicInteger();
        scenario.onActivity(activity -> {
            android.widget.LinearLayout container = activity.findViewById(
                com.termux.localgames.R.id.local_games_component_sections);
            sections.set(container.getChildCount());
            rows.set(componentRowCount(activity));
            android.widget.LinearLayout containers = container.getChildAt(1).findViewById(
                com.termux.localgames.R.id.local_games_component_section_items);
            View firstWine = containers.getChildAt(0);
            assertEquals("Wine 9.3 Vanilla", ((android.widget.TextView) firstWine.findViewById(
                com.termux.localgames.R.id.local_games_component_name)).getText().toString());
            assertTrue(((android.widget.TextView) firstWine.findViewById(
                com.termux.localgames.R.id.local_games_component_metadata)).getText().toString()
                .contains("wine-9.3-vanilla-wow64"));
        });
        assertEquals(7, sections.get());
        assertEquals(17, rows.get());
    }

    private static int componentRowCount(android.app.Activity activity) {
        android.widget.LinearLayout sections = activity.findViewById(
            com.termux.localgames.R.id.local_games_component_sections);
        int rows = 0;
        for (int index = 0; index < sections.getChildCount(); index++) {
            View section = sections.getChildAt(index);
            android.widget.LinearLayout items = section.findViewById(
                com.termux.localgames.R.id.local_games_component_section_items);
            rows += items.getChildCount();
        }
        return rows;
    }

    private static void assertLostPermissionState(ActivityScenario<GameImportActivity> scenario,
                                                   String treeUri) {
        scenario.onActivity(activity -> {
            android.widget.TextView directory = activity.findViewById(
                com.termux.localgames.R.id.local_game_import_directory);
            android.widget.TextView error = activity.findViewById(
                com.termux.localgames.R.id.local_game_import_error);
            View form = activity.findViewById(com.termux.localgames.R.id.local_game_import_form);
            View choose = activity.findViewById(
                com.termux.localgames.R.id.local_game_import_choose_directory);
            assertEquals(treeUri, directory.getText().toString());
            assertEquals(View.VISIBLE, error.getVisibility());
            assertEquals(View.GONE, form.getVisibility());
            assertTrue(choose.isEnabled());
        });
    }

    private static FileGameRepository repository(Context context) {
        return new FileGameRepository(new GameStoragePaths(context.getFilesDir())
            .getLibraryDirectory());
    }

    private static Game game(String id, String name, String rootUri) {
        return new Game(id, name, rootUri, "Game.exe", ".",
            Arrays.asList("--windowed"), "", 0);
    }

    private static boolean waitForLibraryGame(ActivityScenario<LocalGamesActivity> scenario,
                                              String name) throws InterruptedException {
        AtomicBoolean found = new AtomicBoolean();
        for (int attempt = 0; attempt < 80; attempt++) {
            scenario.onActivity(activity -> {
                android.view.ViewGroup rows = activity.findViewById(
                    com.termux.localgames.R.id.local_games_library_items);
                for (int index = 0; index < rows.getChildCount(); index++) {
                    android.widget.TextView title = rows.getChildAt(index).findViewById(
                        com.termux.localgames.R.id.local_game_name);
                    android.widget.TextView access = rows.getChildAt(index).findViewById(
                        com.termux.localgames.R.id.local_game_access_status);
                    if (name.contentEquals(title.getText())) {
                        found.set(access.getText().toString().equals(activity.getString(
                            com.termux.localgames.R.string.local_game_permission_lost)));
                    }
                }
            });
            if (found.get()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean waitForDetail(ActivityScenario<GameDetailActivity> scenario,
                                         String name) throws InterruptedException {
        AtomicBoolean found = new AtomicBoolean();
        for (int attempt = 0; attempt < 80; attempt++) {
            scenario.onActivity(activity -> {
                android.widget.TextView title = activity.findViewById(
                    com.termux.localgames.R.id.game_detail_name);
                android.widget.TextView access = activity.findViewById(
                    com.termux.localgames.R.id.game_detail_access_status);
                found.set(name.contentEquals(title.getText()) && access.getText().toString().equals(
                    activity.getString(com.termux.localgames.R.string.local_game_permission_lost)));
            });
            if (found.get()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean waitForRuntimeProfile(
        ActivityScenario<GameRuntimeProfileActivity> scenario, String wine)
        throws InterruptedException {
        AtomicBoolean found = new AtomicBoolean();
        for (int attempt = 0; attempt < 100; attempt++) {
            scenario.onActivity(activity -> {
                android.widget.TextView name = activity.findViewById(
                    com.termux.localgames.R.id.runtime_profile_game_name);
                android.widget.TextView wineView = activity.findViewById(
                    com.termux.localgames.R.id.runtime_profile_wine);
                View content = activity.findViewById(
                    com.termux.localgames.R.id.runtime_profile_content);
                found.set(content.getVisibility() == View.VISIBLE &&
                    "Runtime Profile Test".contentEquals(name.getText()) &&
                    wine.contentEquals(wineView.getText()));
            });
            if (found.get()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean waitForSavedProfile(FileRuntimeProfileRepository repository,
                                               String gameId, String wine) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (repository.find(gameId).filter(profile ->
                wine.equals(profile.getWinePackage())).isPresent()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static void assertRuntimeExecutionMode(
        ActivityScenario<GameRuntimeProfileActivity> scenario, int expectedString) {
        scenario.onActivity(activity -> assertEquals(activity.getString(expectedString),
            ((android.widget.AutoCompleteTextView) activity.findViewById(
                com.termux.localgames.R.id.runtime_profile_execution_mode))
                .getText().toString()));
    }

    private static boolean waitForGameDeletion(FileGameRepository repository, String gameId)
        throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            if (!repository.find(gameId).isPresent()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean waitForAssetCategories(ActivityScenario<GameAssetsActivity> scenario)
        throws InterruptedException {
        AtomicBoolean ready = new AtomicBoolean();
        for (int attempt = 0; attempt < 100; attempt++) {
            scenario.onActivity(activity -> ready.set(((android.widget.LinearLayout)
                activity.findViewById(com.termux.localgames.R.id.game_assets_categories))
                .getChildCount() == 5));
            if (ready.get()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static boolean waitForSnapshotRows(ActivityScenario<GameAssetsActivity> scenario)
        throws InterruptedException {
        AtomicBoolean ready = new AtomicBoolean();
        for (int attempt = 0; attempt < 100; attempt++) {
            scenario.onActivity(activity -> ready.set(((android.widget.LinearLayout)
                activity.findViewById(com.termux.localgames.R.id.game_assets_snapshot_items))
                .getChildCount() > 0));
            if (ready.get()) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static byte[] pngBytes(int color) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        bitmap.recycle();
        return output.toByteArray();
    }
}
