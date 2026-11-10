package com.termux.ui.wine.activity;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import com.termux.ui.api.LocalGamesHost;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.api.ResolvedGameDirectory;
import com.termux.ui.wine.data.FileGameRepository;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.domain.Game;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfilePreset;
import com.termux.ui.wine.domain.RuntimeProfilePresets;
import com.termux.ui.wine.runtime.RootfsSetupRecipe;

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
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w840dp-h660dp-land")
public class GameControllerMappingEditorTest {
    private Object oldFactory;
    private File root;

    @Before public void installHost() throws Exception {
        oldFactory = field(LocalGames.class, null, "hostFactory");
        root = new File(RuntimeEnvironment.getApplication().getFilesDir(), "controller-test-game");
        assertTrue(root.isDirectory() || root.mkdirs());
        LocalGames.install(context -> new LocalGamesHost() {
            @Override public boolean isRuntimeAvailable() { return true; }
            @Override public void forceStopRuntimeProcesses(List<String> paths) {}
            @Override public ResolvedGameDirectory resolveGameDirectory(String uri) {
                return ResolvedGameDirectory.resolved(root.getAbsolutePath());
            }
        });
    }

    @After public void restoreHost() throws Exception {
        Field factory = LocalGames.class.getDeclaredField("hostFactory");
        factory.setAccessible(true);
        factory.set(null, oldFactory);
    }

    @Test public void mappingPickerPreservesTouchLayoutAndBothLibraryChoices() throws Exception {
        for (String wine : new String[] {"glibc_wine_9.3-vanilla", "glibc_proton_11.0",
                "rootfs_proton_11.0", "rootfs_hangover_11.16"}) {
            Context context = RuntimeEnvironment.getApplication();
            GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
            String id = "mapping-" + UUID.randomUUID();
            boolean proot = wine.startsWith("rootfs_");
            RuntimeProfile base = RuntimeProfilePresets.create(id, RuntimeProfilePreset.RECOMMENDED);
            Map<String, String> environment = new LinkedHashMap<>(base.getEnvironment());
            environment.put("GAMES_DLL_DINPUT", "native,builtin");
            environment.put("GAMES_DLL_XINPUT", "builtin");
            RuntimeProfile original = new RuntimeProfile(id, wine, base.getGraphicsDriver(), base.getDxWrapper(),
                base.getAudioDriver(), base.getResolution(), base.getBox64Preset(), environment, "xinput:3",
                base.getLaunchExecutionMode(), base.getComponentVersions(), proot
                    ? GameRuntimeBackendType.ROOTFS_PROOT : GameRuntimeBackendType.GLIBC_TERMUX_BOX,
                proot ? RootfsSetupRecipe.DEFAULT_PACKAGE : "", proot ? "mapping-container" : "default", false, true);
            FileRuntimeProfileRepository profiles = new FileRuntimeProfileRepository(paths.getProfilesDirectory());
            profiles.save(original);
            new FileGameRepository(paths.getLibraryDirectory()).save(new Game(id, "Controller test",
                Uri.fromFile(root).toString(), "game.exe", ".", Collections.emptyList(), "", 0));
            try (ActivityController<GameDetailActivity> controller = Robolectric.buildActivity(GameDetailActivity.class,
                    new Intent(context, GameDetailActivity.class).putExtra(GameDetailActivity.EXTRA_GAME_ID, id))
                    .create().start().resume().visible()) {
                GameDetailActivity activity = controller.get();
                flush(activity);
                activity.findViewById(R.id.game_detail_runtime_profile).performClick();
                GameRuntimeOptionsView options = (GameRuntimeOptionsView) field(activity.getClass(), activity, "runtimeOptionsView");
                flush(options);
                clickText(options, activity.getString(R.string.local_game_runtime_menu_libraries));
                assertNull("Mapping belongs in Advanced, not Function Library",
                    findText(options, activity.getString(R.string.local_game_runtime_gamepad_mapping)));
                View directInputLabel = findText(options, "DirectInput");
                assertNotNull(directInputLabel);
                View directInputRow = (View) directInputLabel.getParent().getParent();
                assertEquals("Only Hangover's marked ARM64 libraries are fixed to builtin",
                    !wine.startsWith("rootfs_hangover_"), directInputRow.isClickable());
                options.navigateBack();
                clickText(options, activity.getString(R.string.local_game_runtime_menu_advanced));
                androidx.appcompat.widget.SwitchCompat xinput = (androidx.appcompat.widget.SwitchCompat)
                    findText(options, activity.getString(R.string.local_game_runtime_enable_xinput));
                androidx.appcompat.widget.SwitchCompat dinput = (androidx.appcompat.widget.SwitchCompat)
                    findText(options, activity.getString(R.string.local_game_runtime_enable_dinput));
                assertFalse(xinput.isChecked());
                assertTrue(dinput.isChecked());
                xinput.setChecked(true);
                dinput.setChecked(false);
                assertTrue(options.hasUnsavedChanges());
                android.os.Bundle draft = options.saveDraftState();
                xinput.setChecked(false);
                dinput.setChecked(true);
                options.restoreDraftState(draft);
                RuntimeProfile restoredDraft = (RuntimeProfile) field(options.getClass(), options, "profile");
                assertTrue(restoredDraft.isXinputEnabled());
                assertFalse(restoredDraft.isDinputEnabled());
                for (int selection : new int[] {1, 0}) {
                    clickText(options, activity.getString(R.string.local_game_runtime_gamepad_mapping));
                    AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
                    assertEquals(2, dialog.getListView().getCount());
                    dialog.getListView().performItemClick(null, selection, selection);
                    RuntimeProfile changed = (RuntimeProfile) field(options.getClass(), options, "profile");
                    assertEquals(selection == 0 ? "xinput:3" : "dinput:3", changed.getInputProfileId());
                    assertEquals(original.getEnvironment(), changed.getEnvironment());
                    assertEquals(original.getWinePackage(), changed.getWinePackage());
                    assertTrue(changed.isXinputEnabled());
                    assertFalse(changed.isDinputEnabled());
                    assertFalse(profiles.find(id).get().isXinputEnabled());
                    assertTrue(profiles.find(id).get().isDinputEnabled());
                    assertEquals("draft must not be persisted before Save", "xinput:3",
                        profiles.find(id).get().getInputProfileId());
                }
            }
        }
    }

    private static Object field(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void flush(Object target) throws Exception {
        for (int i = 0; i < 4; i++) {
            ((ExecutorService) field(target.getClass(), target, "ioExecutor")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private static View findText(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findText(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void clickText(View root, String text) {
        View found = findText(root, text);
        assertNotNull("Missing row: " + text, found);
        while (found != null && !found.isClickable()) {
            found = found.getParent() instanceof View ? (View) found.getParent() : null;
        }
        assertNotNull("Row is not clickable: " + text, found);
        assertTrue(found.performClick());
    }
}
