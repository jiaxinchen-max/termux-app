package com.termux.ui.wine.activity;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import com.termux.ui.R;
import com.termux.ui.activity.LocalGamesActivity;
import com.termux.ui.activity.RuntimeComponentVersionsActivity;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.api.*;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.api.ResolvedGameDirectory;
import com.termux.ui.wine.data.*;
import com.termux.ui.wine.domain.*;
import com.termux.ui.wine.runtime.RootfsSetupRecipe;
import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w840dp-h660dp-land")
public class GameEditorActionsTest {
    private Object oldFactory;
    private int kills;
    private File root;
    @Before public void installHost() throws Exception {
        Field field = LocalGames.class.getDeclaredField("hostFactory"); field.setAccessible(true);
        oldFactory = field.get(null);
        root = new File(RuntimeEnvironment.getApplication().getFilesDir(), "test-game-root");
        assertTrue(root.isDirectory() || root.mkdirs());
        LocalGames.install(context -> new LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public List<RuntimeComponentVariant> listInstalledRuntimeComponentVariants(
                    CustomRuntimeComponent.Kind kind, GameRuntimeBackendType backend) {
                if (kind != CustomRuntimeComponent.Kind.WINE) return Collections.emptyList();
                return Arrays.asList(new RuntimeComponentVariant("glibc_wine_9.3-vanilla", "Wine 9.3", kind, backend),
                    new RuntimeComponentVariant("proton_11.0", "Proton 11.0", kind, backend),
                    new RuntimeComponentVariant("rootfs_hangover_11.16", "Hangover 11.16", kind, backend));
            }
            public void forceStopRuntimeProcesses(List<String> scopes) { kills++; }
            public ResolvedGameDirectory resolveGameDirectory(String uri) {
                return ResolvedGameDirectory.resolved(root.getAbsolutePath());
            }
        });
    }
    @After public void restoreHost() throws Exception {
        Field field = LocalGames.class.getDeclaredField("hostFactory"); field.setAccessible(true); field.set(null, oldFactory);
    }

    @Test public void newImportsCannotLaunchButCanEditAndDeleteOnBothBackends() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = createGame(backend);
            try (ActivityController<GameDetailActivity> controller = open(id)) {
                GameDetailActivity activity = controller.get(); flush(activity);
                assertTrue("entry must force-stop", kills > 0);
                assertFalse(activity.findViewById(R.id.game_detail_launch).isEnabled());
                com.google.android.material.button.MaterialButton launch = activity.findViewById(R.id.game_detail_launch);
                int disabledColor = launch.getBackgroundTintList().getColorForState(new int[] {-android.R.attr.state_enabled}, 0);
                int enabledColor = launch.getBackgroundTintList().getColorForState(new int[] {android.R.attr.state_enabled}, 0);
                assertNotEquals("disabled Launch must look disabled", enabledColor, disabledColor);
                for (int view : new int[] {R.id.game_detail_name, R.id.game_detail_runtime_profile,
                        R.id.game_detail_assets, R.id.game_detail_save, R.id.game_detail_delete})
                    assertTrue(activity.findViewById(view).isEnabled());
                activity.findViewById(R.id.game_detail_launch).performClick(); flush(activity);
                assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
                Configuration rotated = new Configuration(activity.getResources().getConfiguration());
                rotated.orientation = Configuration.ORIENTATION_PORTRAIT;
                activity.onConfigurationChanged(rotated); flush(activity);
                assertFalse(activity.findViewById(R.id.game_detail_launch).isEnabled());
                assertTrue(activity.findViewById(R.id.game_detail_delete).isEnabled());
            }
        }
    }

    @Test public void preparingGameIsStoppedInsteadOfRejectingEditor() throws Exception {
        String id = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        GameStoragePaths paths = new GameStoragePaths(RuntimeEnvironment.getApplication().getFilesDir());
        FilePrefixSetupTaskRepository tasks = new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory());
        String task = "test-prefix-" + UUID.randomUUID();
        tasks.save(PrefixSetupTask.queued(task, id, "glibc_wine_9.3-vanilla", 1));
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            flush(controller.get());
            assertFalse(controller.get().isFinishing());
            assertEquals(RuntimeSetupTaskState.CANCELLED, tasks.find(task).get().getState());
            assertTrue(controller.get().findViewById(R.id.game_detail_delete).isEnabled());
            assertFalse(controller.get().findViewById(R.id.game_detail_launch).isEnabled());
        }
    }

    @Test public void unsavedEditsDisableLaunchAndDiscardRestoresAvailableState() throws Exception {
        String id = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            Field ready = GameDetailActivity.class.getDeclaredField("runtimeReady"); ready.setAccessible(true); ready.set(activity, true);
            Method update = GameDetailActivity.class.getDeclaredMethod("updatePreparedActions"); update.setAccessible(true); update.invoke(activity);
            assertTrue(activity.findViewById(R.id.game_detail_launch).isEnabled());
            android.widget.TextView name = activity.findViewById(R.id.game_detail_name);
            String original = name.getText().toString(); name.setText("Unsaved name");
            assertFalse(activity.findViewById(R.id.game_detail_launch).isEnabled());
            assertTrue(activity.findViewById(R.id.game_detail_delete).isEnabled());
            name.setText(original);
            assertTrue(activity.findViewById(R.id.game_detail_launch).isEnabled());
            // A stale enabled button still cannot bypass the fresh filesystem check.
            activity.findViewById(R.id.game_detail_launch).performClick(); flush(activity);
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        }
    }

    private String createGame(GameRuntimeBackendType backend) throws Exception {
        Context context = RuntimeEnvironment.getApplication(); GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        String id = "test-" + UUID.randomUUID();
        new FileGameRepository(paths.getLibraryDirectory()).save(new Game(id, "Game", Uri.fromFile(root).toString(),
            "game.exe", ".", Collections.emptyList(), "", 0));
        RuntimeProfile preset = RuntimeProfilePresets.create(id, RuntimeProfilePreset.RECOMMENDED);
        RuntimeProfile profile = backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX ? preset
            : new RuntimeProfile(id, "rootfs_hangover_11.16", "rootfs-llvmpipe", "rootfs-wined3d", "pulseaudio",
                preset.getResolution(), preset.getBox64Preset(), preset.getEnvironment(), preset.getInputProfileId(),
                preset.getLaunchExecutionMode(), preset.getComponentVersions(), backend,
                RootfsSetupRecipe.DEFAULT_PACKAGE, "container-" + UUID.randomUUID());
        new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(profile);
        return id;
    }

    @Test public void editorWinePickerReturnsAnUnsavedDraftOnBothBackends() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = createGame(backend);
            try (ActivityController<GameDetailActivity> controller = open(id)) {
                GameDetailActivity activity = controller.get(); flush(activity);
                activity.findViewById(R.id.game_detail_runtime_profile).performClick();
                GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView");
                flushIo(options);
                String original = profile(options).getWinePackage();
                assertNull("global component management belongs in Settings",
                    findText(options, activity.getString(R.string.local_game_runtime_backend_components)));
                clickText(options, activity.getString(R.string.local_game_runtime_common_wine_package));
                Intent picker = Shadows.shadowOf(activity).getNextStartedActivity();
                assertEquals(RuntimeComponentVersionsActivity.class.getName(), picker.getComponent().getClassName());
                assertEquals(original, picker.getStringExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID));
                assertEquals(backend.getStorageValue(), picker.getStringExtra(RuntimeComponentVersionsActivity.EXTRA_BACKEND));
                try (ActivityController<RuntimeComponentVersionsActivity> versions = Robolectric.buildActivity(
                        RuntimeComponentVersionsActivity.class, picker).create().start().resume()) {
                    versions.recreate();
                    RuntimeComponentVersionsActivity versionActivity = versions.get(); flushIo(versionActivity);
                    View selectedRow = clickableParent(findText(versionActivity.getWindow().getDecorView(),
                        backend == GameRuntimeBackendType.ROOTFS_PROOT ? "Hangover 11.16" : "Wine 9.3"));
                    assertTrue("current Wine must have selected styling/check", selectedRow.isSelected());
                    clickText(versionActivity.getWindow().getDecorView(), "Proton 11.0");
                    assertEquals(android.app.Activity.RESULT_OK, Shadows.shadowOf(versionActivity).getResultCode());
                    Shadows.shadowOf(activity).receiveResult(picker, android.app.Activity.RESULT_OK,
                        Shadows.shadowOf(versionActivity).getResultIntent());
                    flushIo(options);
                    assertEquals("proton_11.0", profile(options).getWinePackage());
                    assertNotNull(findText(options, "Proton 11.0"));
                    assertTrue(options.hasUnsavedChanges());
                    assertFalse(activity.findViewById(R.id.game_detail_launch).isEnabled());
                    assertEquals("selection must not save before Save", original,
                        new FileRuntimeProfileRepository(new GameStoragePaths(activity.getFilesDir())
                            .getProfilesDirectory()).find(id).get().getWinePackage());
                    Method save = GameRuntimeOptionsView.class.getDeclaredMethod("saveAndFinish");
                    save.setAccessible(true); save.invoke(options); flushIo(options);
                    assertEquals("Save must persist the exact chosen Wine", "proton_11.0",
                        new FileRuntimeProfileRepository(new GameStoragePaths(activity.getFilesDir())
                            .getProfilesDirectory()).find(id).get().getWinePackage());
                }
            }
        }
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port")
    public void cardOverflowOnlyOffersEditAndRemoveInPortrait() throws Exception {
        assertCardOverflowActions();
    }

    @Test @Config(qualifiers = "w1000dp-h600dp-land")
    public void cardOverflowOnlyOffersEditAndRemoveInLandscape() throws Exception {
        assertCardOverflowActions();
    }

    private void assertCardOverflowActions() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = createGame(backend);
            try (ActivityController<LocalGamesActivity> controller = Robolectric.buildActivity(
                    LocalGamesActivity.class, LocalGames.createLaunchIntent(RuntimeEnvironment.getApplication()))
                    .create().start().resume().visible()) {
                LocalGamesActivity activity = controller.get(); flushLibrary(activity);
                GameStoragePaths paths = new GameStoragePaths(activity.getFilesDir());
                FileGameRepository games = new FileGameRepository(paths.getLibraryDirectory());
                Method menu = LocalGamesActivity.class.getDeclaredMethod("showActionsTextMenu", GameLibraryItem.class, View.class);
                menu.setAccessible(true);
                for (GameAccessState state : GameAccessState.values()) {
                    GameLibraryItem item = new GameLibraryItem(games.find(id).get(), state);
                    for (int action : new int[] {R.string.local_game_action_edit, R.string.local_game_action_remove}) {
                        menu.invoke(activity, item, activity.findViewById(R.id.local_games_library_items));
                        android.widget.PopupWindow popup = (android.widget.PopupWindow) field(activity, "moreMenuPopup");
                        assertTrue(popup.isShowing());
                        android.widget.LinearLayout rows = (android.widget.LinearLayout)
                            ((android.widget.ScrollView) popup.getContentView()).getChildAt(0);
                        assertEquals(2, rows.getChildCount());
                        assertEquals(activity.getString(R.string.local_game_action_edit),
                            ((android.widget.TextView) rows.getChildAt(0)).getText().toString());
                        assertEquals(activity.getString(R.string.local_game_action_remove),
                            ((android.widget.TextView) rows.getChildAt(1)).getText().toString());
                        clickText(rows, activity.getString(action));
                        assertFalse(popup.isShowing());
                        if (action == R.string.local_game_action_edit) {
                            Intent edit = Shadows.shadowOf(activity).getNextStartedActivity();
                            assertEquals(GameDetailActivity.class.getName(), edit.getComponent().getClassName());
                            assertEquals(id, edit.getStringExtra(GameDetailActivity.EXTRA_GAME_ID));
                        } else {
                            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                                org.robolectric.shadows.ShadowDialog.getLatestDialog();
                            assertTrue(dialog.isShowing());
                            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
                            assertTrue(games.find(id).isPresent());
                        }
                    }
                }
            }
        }
    }

    @Test public void coreSettingsKeepEnvironmentActionsWithoutComponentEntry() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            try (ActivityController<LocalGamesActivity> controller = Robolectric.buildActivity(
                    LocalGamesActivity.class, LocalGames.createLaunchIntent(RuntimeEnvironment.getApplication()))
                    .create().start().resume().visible()) {
                LocalGamesActivity activity = controller.get(); flushLibrary(activity);
                Method navigation = LocalGamesActivity.class.getDeclaredMethod("onNavigationDestination", int.class);
                navigation.setAccessible(true); navigation.invoke(activity, R.id.local_games_navigation_settings);
                View menu = activity.findViewById(R.id.local_games_settings_menu);
                clickText(menu, activity.getString(R.string.local_games_settings_core_components));
                clickText(menu, activity.getString(backend == GameRuntimeBackendType.ROOTFS_PROOT
                    ? R.string.collection_mode_proot : R.string.collection_mode_native));
                assertEnvironmentMenu(activity, backend);
                controller.recreate(); activity = controller.get(); flushLibrary(activity);
                assertEnvironmentMenu(activity, backend);
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals("CORE_COMPONENTS", field(activity, "settingsPage").toString());
                activity.getOnBackPressedDispatcher().onBackPressed();
                assertEquals("ROOT", field(activity, "settingsPage").toString());
            }
        }
    }

    @Test public void coreSettingsRouteHandlesNewActivityAndReusedActivity() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            try (ActivityController<LocalGamesActivity> controller = Robolectric.buildActivity(
                    LocalGamesActivity.class, LocalGames.createCoreComponentsIntent(
                        RuntimeEnvironment.getApplication(), backend)).create().start().resume().visible()) {
                LocalGamesActivity activity = controller.get(); flushLibrary(activity);
                assertEnvironmentMenu(activity, backend);
                assertFalse(activity.getIntent().hasExtra(LocalGamesActivity.EXTRA_CORE_BACKEND));
                GameRuntimeBackendType other = backend == GameRuntimeBackendType.ROOTFS_PROOT
                    ? GameRuntimeBackendType.GLIBC_TERMUX_BOX : GameRuntimeBackendType.ROOTFS_PROOT;
                controller.newIntent(LocalGames.createCoreComponentsIntent(RuntimeEnvironment.getApplication(), other));
                flushLibrary(activity); assertEnvironmentMenu(activity, other);
                controller.recreate(); activity = controller.get(); flushLibrary(activity);
                assertEnvironmentMenu(activity, other);
                assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            }
        }
    }

    private static void assertEnvironmentMenu(LocalGamesActivity activity, GameRuntimeBackendType backend)
            throws Exception {
        View menu = activity.findViewById(R.id.local_games_settings_menu);
        assertEquals(backend == GameRuntimeBackendType.ROOTFS_PROOT ? "PROOT" : "NATIVE",
            field(activity, "settingsPage").toString());
        assertEquals(View.VISIBLE, activity.findViewById(R.id.local_games_settings_page).getVisibility());
        assertNotNull(findText(menu, activity.getString(R.string.local_games_settings_status)));
        View build = findText(menu, activity.getString(R.string.local_games_environment_build_from_network));
        assertNotNull(build); assertTrue(build.isEnabled());
        assertNull(findText(menu, activity.getString(R.string.local_game_runtime_backend_components)));
        if (backend == GameRuntimeBackendType.ROOTFS_PROOT) {
            assertNotNull(findText(menu, activity.getString(R.string.local_games_environment_restore_from_file)));
            assertNotNull(findText(menu, activity.getString(R.string.local_games_environment_backup_to_file)));
        }
    }

    @Test public void launchRepairOpensCoreSettingsForTheGamesBackend() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = createGame(backend);
            try (ActivityController<GameLaunchActivity> controller = Robolectric.buildActivity(
                    GameLaunchActivity.class, GameLaunchActivity.createTaskIntent(
                        RuntimeEnvironment.getApplication(), id, "missing-task"))
                    .create().start().resume().visible()) {
                GameLaunchActivity activity = controller.get(); flushIo(activity);
                Method action = GameLaunchActivity.class.getDeclaredMethod("configureAction", LaunchPresentation.RecoveryAction.class);
                action.setAccessible(true); action.invoke(activity, LaunchPresentation.RecoveryAction.COMPONENTS);
                activity.findViewById(R.id.game_launch_action).performClick(); flushIo(activity);
                Intent manager = Shadows.shadowOf(activity).getNextStartedActivity();
                assertNotNull(manager);
                assertEquals(LocalGamesActivity.class.getName(), manager.getComponent().getClassName());
                assertEquals(backend.getStorageValue(), manager.getStringExtra(LocalGamesActivity.EXTRA_CORE_BACKEND));
                assertFalse(manager.hasExtra("com.termux.ui.extra.COMPONENT_GAME_ID"));
            }
        }
    }

    @Test public void cancellingGameComponentPickerKeepsCurrentWine() throws Exception {
        String id = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.findViewById(R.id.game_detail_runtime_profile).performClick();
            GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
            String original = profile(options).getWinePackage();
            clickText(options, activity.getString(R.string.local_game_runtime_common_wine_package));
            Intent intent = Shadows.shadowOf(activity).getNextStartedActivity();
            Shadows.shadowOf(activity).receiveResult(intent, android.app.Activity.RESULT_CANCELED, null);
            assertEquals(original, profile(options).getWinePackage());
            assertFalse(options.hasUnsavedChanges());
        }
    }

    @Test public void editorDraftSurvivesRecreationWhileComponentPickerIsOpen() throws Exception {
        String id = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.findViewById(R.id.game_detail_runtime_profile).performClick();
            GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
            options.restoreComponentSelection(selection("proton_11.0", GameRuntimeBackendType.GLIBC_TERMUX_BOX));
            flushIo(options);
            android.os.Bundle draft = options.saveDraftState(); draft.putString("resolution", "1920x1080");
            options.restoreDraftState(draft);
            clickText(options, activity.getString(R.string.local_game_runtime_common_wine_package));
            org.robolectric.shadows.ShadowActivity.IntentForResult pending =
                Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals("proton_11.0", pending.intent.getStringExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID));
            controller.recreate(); activity = controller.get(); flush(activity);
            options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); assertNotNull(options); flushIo(options);
            assertEquals("proton_11.0", profile(options).getWinePackage());
            assertEquals("1920x1080", profile(options).getResolution());
            assertTrue(options.hasUnsavedChanges());
            activity.getActivityResultRegistry().dispatchResult(pending.requestCode, android.app.Activity.RESULT_OK,
                selection("glibc_wine_10.0", GameRuntimeBackendType.GLIBC_TERMUX_BOX));
            flushIo(options);
            assertEquals("glibc_wine_10.0", profile(options).getWinePackage());
            assertEquals("1920x1080", profile(options).getResolution());
        }
    }

    @Test public void nativeBox64PickerReturnsExactVersionAndMarksTheSavedOne() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        LocalGames.install(ignored -> new LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public boolean isRuntimeComponentAvailable(String id) { return true; }
        });
        com.termux.ui.components.index.ComponentIndex index;
        try (java.io.InputStream stream = context.getAssets().open("termux-app-packages/index-v1.json")) {
            index = new com.termux.ui.components.index.ComponentIndexParser().parse(stream);
        }
        File installRoot = new com.termux.ui.components.ComponentStoragePaths(context.getFilesDir()).getInstallDirectory();
        for (String id : new String[] {"glibc_box64_0.2.7", "glibc_box64_0.4.4"}) {
            com.termux.ui.components.index.ComponentDescriptor descriptor = index.find(id).get();
            File packageRoot = new File(installRoot, id);
            File version = new File(packageRoot, "versions/test"); assertTrue(version.mkdirs());
            java.nio.file.Files.write(new File(packageRoot, "active.properties").toPath(),
                "schemaVersion=1\nactive=test\nprevious=\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(new File(version, ".games-component.properties").toPath(),
                ("schemaVersion=1\npackageName=" + id + "\nversion=" + descriptor.getVersion() +
                    "\nsha256=" + descriptor.getSha256() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        for (String current : new String[] {"", "glibc_box64_0.4.4"}) {
            Intent intent = RuntimeComponentVersionsActivity.createSelectIntent(context,
                CustomRuntimeComponent.Kind.BOX64, GameRuntimeBackendType.GLIBC_TERMUX_BOX, current, null);
            try (ActivityController<RuntimeComponentVersionsActivity> controller = Robolectric.buildActivity(
                    RuntimeComponentVersionsActivity.class, intent).create().start().resume()) {
                RuntimeComponentVersionsActivity activity = controller.get(); flushIo(activity);
                View decor = activity.getWindow().getDecorView();
                String selectedName = current.isEmpty() ? "box64_0.2.7" : "box64_0.4.4";
                assertTrue(clickableParent(findText(decor, selectedName)).isSelected());
                String next = current.isEmpty() ? "0.4.4" : "0.2.7";
                clickText(decor, "box64_" + next);
                assertEquals(android.app.Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
                assertEquals("glibc_box64_" + next, Shadows.shadowOf(activity).getResultIntent()
                    .getStringExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID));
            }
        }
    }

    @Test public void prootBox64PageOffersPrebuiltDownloadWithoutAnInstalledCopy() throws Exception {
        Intent intent = RuntimeComponentVersionsActivity.createSelectIntent(RuntimeEnvironment.getApplication(),
            CustomRuntimeComponent.Kind.BOX64, GameRuntimeBackendType.ROOTFS_PROOT, "", RuntimeTranslator.BOX64);
        try (ActivityController<RuntimeComponentVersionsActivity> controller = Robolectric.buildActivity(
                RuntimeComponentVersionsActivity.class, intent).create().start().resume()) {
            RuntimeComponentVersionsActivity activity = controller.get(); flushIo(activity);
            View decor = activity.getWindow().getDecorView();
            assertNotNull(findText(decor, "box64_0.4.5"));
            assertNotNull(findText(decor, activity.getString(R.string.component_versions_online)));
            assertNotNull(findText(decor, activity.getString(R.string.component_versions_download)));
            assertNotNull(findText(decor, activity.getString(R.string.component_versions_empty)));
            assertNull(findText(decor, "box64_0.2.7"));
            assertNull(findText(decor, "box64_0.4.4"));
            assertEquals(android.app.Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
        }
    }

    @Test public void prootBox64PickerUsesExternalReceiptAndReturnsExactDefaultId() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        com.termux.ui.components.index.ComponentDescriptor descriptor;
        try (java.io.InputStream stream = context.getAssets().open("termux-app-packages/index-v1.json")) {
            descriptor = new com.termux.ui.components.index.ComponentIndexParser().parse(stream)
                .find("rootfs_box64_0.4.5").get();
        }
        File packageRoot = new File(new com.termux.ui.components.ComponentStoragePaths(context.getFilesDir())
            .getInstallDirectory(), descriptor.getId());
        File version = new File(packageRoot, "versions/test"); assertTrue(version.mkdirs());
        java.nio.file.Files.write(new File(packageRoot, "active.properties").toPath(),
            "schemaVersion=1\nactive=test\nprevious=\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.write(new File(version, ".games-component.properties").toPath(),
            ("schemaVersion=1\npackageName=" + descriptor.getId() + "\nversion=" + descriptor.getVersion() +
                "\nsha256=" + descriptor.getSha256() + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(new File(new GameStoragePaths(context.getFilesDir()).getSharedRootfsDirectory(),
            "opt/box64/rootfs_box64_0.4.5/bin/box64").exists());
        for (String current : new String[] {"", "rootfs_box64_0.4.5"}) {
            Intent intent = RuntimeComponentVersionsActivity.createSelectIntent(context,
                CustomRuntimeComponent.Kind.BOX64, GameRuntimeBackendType.ROOTFS_PROOT, current, RuntimeTranslator.BOX64);
            try (ActivityController<RuntimeComponentVersionsActivity> controller = Robolectric.buildActivity(
                    RuntimeComponentVersionsActivity.class, intent).create().start().resume()) {
                RuntimeComponentVersionsActivity activity = controller.get(); flushIo(activity);
                View decor = activity.getWindow().getDecorView();
                assertTrue(clickableParent(findText(decor, "box64_0.4.5")).isSelected());
                assertNull(findText(decor, activity.getString(R.string.component_versions_online)));
                clickText(decor, "box64_0.4.5");
                assertEquals(android.app.Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
                assertEquals("rootfs_box64_0.4.5", Shadows.shadowOf(activity).getResultIntent()
                    .getStringExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID));
            }
        }
        new File(version, ".games-component.properties").delete(); assertTrue(version.delete());
        Intent intent = RuntimeComponentVersionsActivity.createSelectIntent(context,
            CustomRuntimeComponent.Kind.BOX64, GameRuntimeBackendType.ROOTFS_PROOT, "", RuntimeTranslator.BOX64);
        try (ActivityController<RuntimeComponentVersionsActivity> controller = Robolectric.buildActivity(
                RuntimeComponentVersionsActivity.class, intent).create().start().resume()) {
            RuntimeComponentVersionsActivity activity = controller.get(); flushIo(activity);
            // A dangling active receipt is a failure, never a usable installed build or a silent fallback.
            assertNull(findText(activity.getWindow().getDecorView(), "box64_0.4.5"));
            assertNotNull(findText(activity.getWindow().getDecorView(), activity.getString(R.string.component_versions_retry)));
        }
    }

    @Test public void prootPrebuiltBox64SelectionSurvivesEditorRecreationAndSave() throws Exception {
        String game = createGame(GameRuntimeBackendType.ROOTFS_PROOT);
        GameStoragePaths paths = new GameStoragePaths(RuntimeEnvironment.getApplication().getFilesDir());
        FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        RuntimeProfile old = profiles.find(game).get();
        profiles.save(new RuntimeProfile(game, "rootfs_wine_10.0", old.getGraphicsDriver(), old.getDxWrapper(),
            old.getAudioDriver(), old.getResolution(), old.getBox64Preset(), old.getEnvironment(), old.getInputProfileId(),
            old.getLaunchExecutionMode(), old.getComponentVersions(), old.getRuntimeBackendType(), old.getRootfsPackage(), old.getContainerId()));
        try (ActivityController<GameDetailActivity> controller = open(game)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.findViewById(R.id.game_detail_runtime_profile).performClick();
            GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
            options.restoreComponentSelection(new Intent()
                .putExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID, "rootfs_box64_0.4.5")
                .putExtra(RuntimeComponentVersionsActivity.EXTRA_KIND, "BOX64")
                .putExtra(RuntimeComponentVersionsActivity.EXTRA_BACKEND, GameRuntimeBackendType.ROOTFS_PROOT.getStorageValue()));
            flushIo(options);
            assertEquals("rootfs_box64_0.4.5", profile(options).getEnvironment().get("GAMES_CUSTOM_BOX64_ID"));
            controller.recreate(); activity = controller.get(); flush(activity);
            options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
            assertEquals("rootfs_box64_0.4.5", profile(options).getEnvironment().get("GAMES_CUSTOM_BOX64_ID"));
            Method save = GameRuntimeOptionsView.class.getDeclaredMethod("saveAndFinish"); save.setAccessible(true);
            save.invoke(options); flushIo(options);
        }
        RuntimeProfile saved = profiles.find(game).get();
        assertEquals("rootfs_box64_0.4.5", saved.getEnvironment().get("GAMES_CUSTOM_BOX64_ID"));
        assertTrue(new com.termux.ui.wine.runtime.ProotDependencyResolver().requiredComponentIds(saved, paths)
            .contains("rootfs_box64_0.4.5"));
    }

    @Test public void savingBox64ForOneGameNeverChangesAnotherGamesVersion() throws Exception {
        String gameA = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        String gameB = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        String[] games = {gameA, gameB};
        String[] builds = {"glibc_box64_0.2.7", "glibc_box64_0.4.4"};
        for (int i = 0; i < games.length; i++) {
            try (ActivityController<GameDetailActivity> controller = open(games[i])) {
                GameDetailActivity activity = controller.get(); flush(activity);
                activity.findViewById(R.id.game_detail_runtime_profile).performClick();
                GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
                Intent result = new Intent().putExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID, builds[i])
                    .putExtra(RuntimeComponentVersionsActivity.EXTRA_KIND, "BOX64")
                    .putExtra(RuntimeComponentVersionsActivity.EXTRA_BACKEND, GameRuntimeBackendType.GLIBC_TERMUX_BOX.getStorageValue());
                options.restoreComponentSelection(result); flushIo(options);
                assertEquals(builds[i], profile(options).getEnvironment().get("GAMES_CUSTOM_BOX64_ID"));
                controller.recreate(); activity = controller.get(); flush(activity);
                options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
                assertEquals(builds[i], profile(options).getEnvironment().get("GAMES_CUSTOM_BOX64_ID"));
                Method save = GameRuntimeOptionsView.class.getDeclaredMethod("saveAndFinish"); save.setAccessible(true);
                save.invoke(options); flushIo(options);
            }
        }
        GameStoragePaths paths = new GameStoragePaths(RuntimeEnvironment.getApplication().getFilesDir());
        FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        assertEquals(builds[0], profiles.find(gameA).get().getEnvironment().get("GAMES_CUSTOM_BOX64_ID"));
        assertEquals(builds[1], profiles.find(gameB).get().getEnvironment().get("GAMES_CUSTOM_BOX64_ID"));
    }

    @Test public void hangoverModesSavePerGameAndSurviveEditorRecreation() throws Exception {
        String gameA = createGame(GameRuntimeBackendType.ROOTFS_PROOT);
        String gameB = createGame(GameRuntimeBackendType.ROOTFS_PROOT);
        GameStoragePaths paths = new GameStoragePaths(RuntimeEnvironment.getApplication().getFilesDir());
        FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
        for (int modeIndex : new int[] {1, 0}) {
            try (ActivityController<GameDetailActivity> controller = open(gameA)) {
                GameDetailActivity activity = controller.get(); flush(activity);
                activity.findViewById(R.id.game_detail_runtime_profile).performClick();
                GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
                assertNotNull(findText(options, "FEX (ARM64EC)"));
                chooseHangoverMode(options, modeIndex);
                String expected = modeIndex == 1 ? "fex" : "wowbox64";
                assertEquals(expected, profile(options).getEnvironment().get("GAMES_HANGOVER_EMULATOR"));
                assertNotNull(findText(options, modeIndex == 1 ? "FEX" : "WowBox64"));
                controller.recreate(); activity = controller.get(); flush(activity);
                options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
                assertNotNull(findText(options, modeIndex == 1 ? "FEX" : "WowBox64"));
                Method save = GameRuntimeOptionsView.class.getDeclaredMethod("saveAndFinish"); save.setAccessible(true);
                save.invoke(options); flushIo(options);
            }
            assertEquals(modeIndex == 1 ? "fex" : "wowbox64",
                profiles.find(gameA).get().getEnvironment().get("GAMES_HANGOVER_EMULATOR"));
            assertFalse(profiles.find(gameB).get().getEnvironment().containsKey("GAMES_HANGOVER_EMULATOR"));
        }
        try (ActivityController<GameDetailActivity> controller = open(gameA)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.findViewById(R.id.game_detail_runtime_profile).performClick();
            GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
            assertNotNull(findText(options, "WowBox64"));
            chooseHangoverMode(options, 1);
            options.navigateBack();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick(); flush(activity);
        }
        assertEquals("wowbox64", profiles.find(gameA).get().getEnvironment().get("GAMES_HANGOVER_EMULATOR"));
    }

    @Test public void hangoverModeRowOnlyAppearsForHangoverWine() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = createGame(backend);
            try (ActivityController<GameDetailActivity> controller = open(id)) {
                GameDetailActivity activity = controller.get(); flush(activity);
                activity.findViewById(R.id.game_detail_runtime_profile).performClick();
                GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
                String label = activity.getString(R.string.local_game_runtime_hangover_emulator);
                if (backend == GameRuntimeBackendType.ROOTFS_PROOT) {
                    assertNotNull(findText(options, label));
                    assertNotNull(findText(options, "WowBox64"));
                    options.restoreComponentSelection(selection("rootfs_proton_11.0", backend)); flushIo(options);
                }
                assertNull(findText(options, label));
            }
        }
    }

    private static void chooseHangoverMode(GameRuntimeOptionsView options, int modeIndex) {
        clickText(options, options.getContext().getString(R.string.local_game_runtime_hangover_emulator));
        androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
            org.robolectric.shadows.ShadowDialog.getLatestDialog();
        android.widget.ListView choices = dialog.getListView();
        choices.performItemClick(choices.getChildAt(modeIndex), modeIndex, choices.getAdapter().getItemId(modeIndex));
    }

    @Test public void preinstalledHangoverIsSelectableBeforeImageSetupWithoutInstallAction() throws Exception {
        Intent intent = RuntimeComponentVersionsActivity.createSelectIntent(RuntimeEnvironment.getApplication(),
            CustomRuntimeComponent.Kind.WINE, GameRuntimeBackendType.ROOTFS_PROOT,
            "rootfs_hangover_11.16", RuntimeTranslator.HANGOVER);
        try (ActivityController<RuntimeComponentVersionsActivity> controller = Robolectric.buildActivity(
                RuntimeComponentVersionsActivity.class, intent).create().start().resume()) {
            RuntimeComponentVersionsActivity activity = controller.get(); flushIo(activity);
            View decor = activity.getWindow().getDecorView();
            assertNotNull(findText(decor, activity.getString(R.string.component_versions_preinstalled)));
            assertNotNull(findText(decor, activity.getString(R.string.component_versions_preinstalled_pending)
                + " · " + activity.getString(R.string.component_versions_current)));
            assertNull(findText(decor, activity.getString(R.string.component_versions_missing, "rootfs_hangover_11.16")));
            assertNull(findText(decor, activity.getString(R.string.component_versions_manual)));
            clickText(decor, "Hangover 11.16");
            assertEquals(android.app.Activity.RESULT_OK, Shadows.shadowOf(activity).getResultCode());
            assertEquals("rootfs_hangover_11.16", Shadows.shadowOf(activity).getResultIntent()
                .getStringExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID));
        }
    }

    @Test public void preinstalledHangoverStatusRefreshesFromIncompleteToReady() throws Exception {
        GameStoragePaths paths = new GameStoragePaths(RuntimeEnvironment.getApplication().getFilesDir());
        File rootfs = paths.getSharedRootfsDirectory(); assertTrue(rootfs.mkdirs());
        Intent intent = RuntimeComponentVersionsActivity.createManageIntent(RuntimeEnvironment.getApplication(),
            CustomRuntimeComponent.Kind.WINE, GameRuntimeBackendType.ROOTFS_PROOT);
        try (ActivityController<RuntimeComponentVersionsActivity> controller = Robolectric.buildActivity(
                RuntimeComponentVersionsActivity.class, intent).create().start().resume()) {
            RuntimeComponentVersionsActivity activity = controller.get(); flushIo(activity);
            assertNotNull(findText(activity.getWindow().getDecorView(),
                activity.getString(R.string.component_versions_preinstalled_incomplete)));
            for (String path : new String[] {"usr/bin/env", "usr/bin/wine", "usr/bin/wineboot",
                    "opt/box64/rootfs_box64_0.4.5/bin/box64", "etc/games-runtime.properties",
                    "usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"}) {
                File file = new File(rootfs, path); file.getParentFile().mkdirs();
                java.nio.file.Files.write(file.toPath(), new byte[] {1}); file.setExecutable(true);
            }
            new File(rootfs, "mnt/games/game").mkdirs(); new File(rootfs, "mnt/games/prefix").mkdirs();
            Method reload = activity.getClass().getDeclaredMethod("reload"); reload.setAccessible(true);
            reload.invoke(activity); flushIo(activity);
            assertNotNull(findText(activity.getWindow().getDecorView(),
                activity.getString(R.string.component_versions_preinstalled_ready)));
            assertNull(findText(activity.getWindow().getDecorView(),
                activity.getString(R.string.component_versions_preinstalled_incomplete)));
        }
    }

    @Test public void globalManagementHasNoGameSelectionAndCannotChooseInstalledRows() throws Exception {
        Intent intent = RuntimeComponentVersionsActivity.createManageIntent(RuntimeEnvironment.getApplication(),
            CustomRuntimeComponent.Kind.WINE, GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<RuntimeComponentVersionsActivity> controller = Robolectric.buildActivity(
                RuntimeComponentVersionsActivity.class, intent).create().start().resume()) {
            RuntimeComponentVersionsActivity activity = controller.get(); flushIo(activity);
            assertNull(findText(activity.getWindow().getDecorView(), activity.getString(R.string.component_versions_current)));
            View label = findText(activity.getWindow().getDecorView(), "Wine 9.3"); assertNotNull(label);
            assertFalse(((View) label.getParent().getParent()).hasOnClickListeners());
            assertEquals(android.app.Activity.RESULT_CANCELED, Shadows.shadowOf(activity).getResultCode());
        }
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port")
    public void narrowEditorKeepsWineDraftAndAcceptsSelectionAfterRecreation() throws Exception {
        String id = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        Intent intent = LocalGames.createRuntimeOptionsIntent(RuntimeEnvironment.getApplication(), id);
        try (ActivityController<GameRuntimeOptionsActivity> controller = Robolectric.buildActivity(
                GameRuntimeOptionsActivity.class, intent).create().start().resume().visible()) {
            GameRuntimeOptionsActivity activity = controller.get();
            GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "optionsView"); flushIo(options);
            options.restoreComponentSelection(selection("proton_11.0", GameRuntimeBackendType.GLIBC_TERMUX_BOX)); flushIo(options);
            clickText(options, activity.getString(R.string.local_game_runtime_common_wine_package));
            org.robolectric.shadows.ShadowActivity.IntentForResult pending = Shadows.shadowOf(activity).getNextStartedActivityForResult();
            assertEquals("proton_11.0", pending.intent.getStringExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID));
            controller.recreate(); activity = controller.get();
            options = (GameRuntimeOptionsView) field(activity, "optionsView"); flushIo(options);
            assertEquals("proton_11.0", profile(options).getWinePackage());
            activity.getActivityResultRegistry().dispatchResult(pending.requestCode, android.app.Activity.RESULT_OK,
                selection("glibc_wine_10.0", GameRuntimeBackendType.GLIBC_TERMUX_BOX));
            flushIo(options);
            assertEquals("glibc_wine_10.0", profile(options).getWinePackage());
            assertTrue(options.hasUnsavedChanges());
        }
    }

    private String stageImport(GameRuntimeBackendType backend) throws Exception {
        return com.termux.ui.wine.importer.QuickGameImport.importGame(
            RuntimeEnvironment.getApplication().getFilesDir(), new File(root, "game.exe"), backend);
    }

    @Test public void discardNewImportFromRuntimeEditorLeavesNoCardOnBothBackends() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = stageImport(backend);
            GameStoragePaths paths = new GameStoragePaths(RuntimeEnvironment.getApplication().getFilesDir());
            try (ActivityController<GameDetailActivity> controller = open(id)) {
                GameDetailActivity activity = controller.get(); flush(activity);
                assertFalse(activity.isFinishing());
                assertFalse(activity.findViewById(R.id.game_detail_launch).isEnabled());
                assertFalse(activity.findViewById(R.id.game_detail_assets).isEnabled());
                assertFalse(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
                activity.findViewById(R.id.game_detail_runtime_profile).performClick();
                GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView");
                flushIo(options);
                assertNotNull(profile(options));
                // No edits: exiting a new import still abandons the unpublished draft.
                options.navigateBack(); flushIo(options);
                assertTrue(activity.isFinishing());
                assertFalse(new com.termux.ui.wine.importer.GameImportDrafts(activity.getFilesDir()).isDraft(id));
                assertFalse(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
                assertTrue(new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory()).list().isEmpty());
            }
        }
    }

    @Test public void changedNewImportDiscardButtonDoesNotPublishGame() throws Exception {
        String id = stageImport(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.findViewById(R.id.game_detail_runtime_profile).performClick();
            GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
            options.restoreComponentSelection(selection("proton_11.0", GameRuntimeBackendType.GLIBC_TERMUX_BOX)); flushIo(options);
            options.navigateBack();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick();
            flushIo(options);
            assertTrue(activity.isFinishing());
            assertFalse(new FileGameRepository(new GameStoragePaths(activity.getFilesDir()).getLibraryDirectory()).find(id).isPresent());
        }
    }

    @Test public void newImportSurvivesRecreationButOnlyRuntimeSavePublishesIt() throws Exception {
        for (GameRuntimeBackendType backend : GameRuntimeBackendType.values()) {
            String id = stageImport(backend);
            try (ActivityController<GameDetailActivity> controller = open(id)) {
                GameDetailActivity activity = controller.get(); flush(activity);
                controller.recreate(); activity = controller.get(); flush(activity);
                assertFalse(activity.isFinishing());
                activity.findViewById(R.id.game_detail_runtime_profile).performClick();
                GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
                String savedWine = profile(options).getWinePackage();
                Method save = GameRuntimeOptionsView.class.getDeclaredMethod("saveAndFinish"); save.setAccessible(true);
                save.invoke(options); flushIo(options);
                GameStoragePaths paths = new GameStoragePaths(activity.getFilesDir());
                assertTrue(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
                assertEquals(savedWine, new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find(id).get().getWinePackage());
                assertFalse(new com.termux.ui.wine.importer.GameImportDrafts(activity.getFilesDir()).isDraft(id));
            }
        }
    }

    @Test public void mainEditorSavePublishesUnchangedImport() throws Exception {
        String id = stageImport(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.findViewById(R.id.game_detail_save).performClick(); flush(activity);
            assertTrue(new FileGameRepository(new GameStoragePaths(activity.getFilesDir()).getLibraryDirectory()).find(id).isPresent());
        }
    }

    @Test public void mainEditorDiscardButtonAbandonsNewImport() throws Exception {
        String id = stageImport(GameRuntimeBackendType.ROOTFS_PROOT);
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.onBackPressed();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertNotNull(dialog);
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick(); flush(activity);
            assertTrue(activity.isFinishing());
            com.termux.ui.wine.importer.GameImportDrafts drafts = new com.termux.ui.wine.importer.GameImportDrafts(activity.getFilesDir());
            assertFalse(drafts.isDraft(id));
            assertFalse(drafts.editingGames(id).find(id).isPresent());
        }
    }

    @Test public void existingGameDiscardKeepsOriginalWineAndCard() throws Exception {
        String id = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.findViewById(R.id.game_detail_runtime_profile).performClick();
            GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
            String original = profile(options).getWinePackage();
            options.restoreComponentSelection(selection("proton_11.0", GameRuntimeBackendType.GLIBC_TERMUX_BOX)); flushIo(options);
            options.navigateBack();
            androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
            dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick(); flush(activity);
            GameStoragePaths paths = new GameStoragePaths(activity.getFilesDir());
            assertTrue(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
            assertEquals(original, new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find(id).get().getWinePackage());
        }
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port")
    public void portraitRuntimeDiscardClosesParentDraftWithoutPublishing() throws Exception {
        String id = stageImport(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> parent = open(id)) {
            GameDetailActivity activity = parent.get(); flush(activity);
            activity.findViewById(R.id.game_detail_runtime_profile).performClick();
            Intent child = Shadows.shadowOf(activity).getNextStartedActivity(); assertNotNull(child);
            parent.pause().stop();
            try (ActivityController<GameRuntimeOptionsActivity> editor = Robolectric.buildActivity(
                    GameRuntimeOptionsActivity.class, child).create().start().resume().visible()) {
                GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(editor.get(), "optionsView"); flushIo(options);
                options.navigateBack(); flushIo(options);
                assertTrue(editor.get().isFinishing());
            }
            parent.start().resume(); flush(activity);
            assertTrue(activity.isFinishing());
            assertFalse(new FileGameRepository(new GameStoragePaths(activity.getFilesDir()).getLibraryDirectory()).find(id).isPresent());
        }
    }

    @Test public void cardAndListShowSavedWineAndRefreshAfterSwitch() throws Exception {
        String id = createGame(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        String hiddenId = stageImport(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<LocalGamesActivity> controller = Robolectric.buildActivity(LocalGamesActivity.class,
                LocalGames.createLaunchIntent(RuntimeEnvironment.getApplication())).create().start().resume().visible()) {
            LocalGamesActivity activity = controller.get(); flushLibrary(activity);
            assertNotNull(findText(activity.getWindow().getDecorView(), activity.getString(R.string.local_game_card_wine, "Wine 9.3")));
            assertFalse(new FileGameRepository(new GameStoragePaths(activity.getFilesDir()).getLibraryDirectory()).find(hiddenId).isPresent());
            androidx.appcompat.widget.Toolbar toolbar = activity.findViewById(R.id.local_games_library_toolbar);
            // Exercise the alternate list binding through the actual view-mode menu action.
            assertNotNull(toolbar);
            {
                toolbar.getMenu().performIdentifierAction(R.id.local_games_action_view_mode, 0);
                flushLibrary(activity);
                assertNotNull(findText(activity.getWindow().getDecorView(), activity.getString(R.string.local_game_card_wine, "Wine 9.3")));
            }
            GameStoragePaths paths = new GameStoragePaths(activity.getFilesDir());
            FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
            RuntimeProfile p = profiles.find(id).get();
            profiles.save(new RuntimeProfile(p.getId(), "proton_11.0", p.getGraphicsDriver(), p.getDxWrapper(), p.getAudioDriver(),
                p.getResolution(), p.getBox64Preset(), p.getEnvironment(), p.getInputProfileId(), p.getLaunchExecutionMode(),
                p.getComponentVersions(), p.getRuntimeBackendType(), p.getRootfsPackage(), p.getContainerId()));
            controller.pause().stop().start().resume(); flushLibrary(activity);
            assertNotNull(findText(activity.getWindow().getDecorView(), activity.getString(R.string.local_game_card_wine, "Proton 11.0")));
        }
    }

    private static void flushLibrary(LocalGamesActivity activity) throws Exception {
        for (int i = 0; i < 4; i++) {
            ((ExecutorService) field(activity, "libraryExecutor")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test public void draftCanChooseWineAndMainSaveUsesThatSelection() throws Exception {
        String id = stageImport(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> controller = open(id)) {
            GameDetailActivity activity = controller.get(); flush(activity);
            activity.findViewById(R.id.game_detail_runtime_profile).performClick();
            GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity, "runtimeOptionsView"); flushIo(options);
            clickText(options, activity.getString(R.string.local_game_runtime_common_wine_package));
            Intent picker = Shadows.shadowOf(activity).getNextStartedActivity(); assertNotNull(picker);
            assertEquals(RuntimeComponentVersionsActivity.class.getName(), picker.getComponent().getClassName());
            options.restoreComponentSelection(selection("proton_11.0", GameRuntimeBackendType.GLIBC_TERMUX_BOX)); flushIo(options);
            GameStoragePaths paths = new GameStoragePaths(activity.getFilesDir());
            assertFalse(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
            activity.findViewById(R.id.game_detail_save).performClick(); flush(activity);
            assertTrue(new FileGameRepository(paths.getLibraryDirectory()).find(id).isPresent());
            assertEquals("proton_11.0", new FileRuntimeProfileRepository(paths.getProfilesDirectory()).find(id).get().getWinePackage());
        }
    }

    @Test public void backBeforeDraftFinishesLoadingDiscardsWithoutCrashing() throws Exception {
        String id = stageImport(GameRuntimeBackendType.GLIBC_TERMUX_BOX);
        try (ActivityController<GameDetailActivity> controller = Robolectric.buildActivity(GameDetailActivity.class,
                new Intent(RuntimeEnvironment.getApplication(), GameDetailActivity.class)
                    .putExtra(GameDetailActivity.EXTRA_GAME_ID, id)).create()) {
            GameDetailActivity activity = controller.get();
            activity.onBackPressed(); flush(activity);
            assertTrue(activity.isFinishing());
            assertFalse(new com.termux.ui.wine.importer.GameImportDrafts(activity.getFilesDir()).isDraft(id));
        }
    }

    private Intent selection(String wine, GameRuntimeBackendType backend) {
        return new Intent().putExtra(RuntimeComponentVersionsActivity.EXTRA_SELECTION_ID, wine)
            .putExtra(RuntimeComponentVersionsActivity.EXTRA_KIND, "WINE")
            .putExtra(RuntimeComponentVersionsActivity.EXTRA_BACKEND, backend.getStorageValue());
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static RuntimeProfile profile(GameRuntimeOptionsView view) throws Exception { return (RuntimeProfile) field(view, "profile"); }
    private static void flushIo(Object target) throws Exception {
        for (int i = 0; i < 4; i++) {
            ((ExecutorService) field(target, "ioExecutor")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static View findText(View root, String text) {
        if (root instanceof android.widget.TextView && text.equals(((android.widget.TextView) root).getText().toString())) return root;
        if (root instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) root).getChildCount(); i++) {
            View found = findText(((android.view.ViewGroup) root).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private static View clickableParent(View view) {
        assertNotNull(view);
        while (view != null && !view.hasOnClickListeners()) view = view.getParent() instanceof View ? (View) view.getParent() : null;
        assertNotNull(view); return view;
    }
    private static void clickText(View root, String text) { assertTrue(clickableParent(findText(root, text)).performClick()); }
    private ActivityController<GameDetailActivity> open(String id) {
        return Robolectric.buildActivity(GameDetailActivity.class,
            new Intent(RuntimeEnvironment.getApplication(), GameDetailActivity.class)
                .putExtra(GameDetailActivity.EXTRA_GAME_ID, id)).create().start().resume().visible();
    }
    private void flush(GameDetailActivity activity) throws Exception {
        Field io = GameDetailActivity.class.getDeclaredField("ioExecutor"); io.setAccessible(true);
        for (int pass = 0; pass < 3; pass++) {
            ((ExecutorService) io.get(activity)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
}
