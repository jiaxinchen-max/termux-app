package com.termux.ui.wine.activity;

import android.app.Activity;
import android.content.Context;
import android.os.Looper;
import androidx.appcompat.app.AppCompatActivity;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.databinding.ActivityGameSessionBinding;
import com.termux.ui.wine.data.LaunchSpecCodec;
import com.termux.ui.wine.data.FileLaunchTaskRepository;
import com.termux.ui.wine.domain.LaunchSpec;
import com.termux.ui.wine.domain.LaunchTask;
import org.robolectric.Shadows;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.termux.ui.R;
import com.termux.ui.wine.data.FileRuntimeProfileRepository;
import com.termux.ui.wine.domain.RuntimeProfile;
import com.termux.ui.wine.domain.RuntimeProfilePreset;
import com.termux.ui.wine.domain.RuntimeProfilePresets;
import com.termux.x11.X11SessionView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w840dp-h660dp-land", shadows = GameControllerPanelTest.ShadowSession.class)
public class GameControllerPanelTest {
    /** Real UI and disk storage; only the external X11 session is simulated. */
    @Implements(X11SessionView.class)
    public static class ShadowSession extends org.robolectric.shadows.ShadowViewGroup {
        int profileId;
        String liveSelection = "xinput";
        boolean xinput = true, dinput = true;
        int apiApplications;
        @Implementation protected void initialize(Context context) {}
        @Implementation protected void bind(AppCompatActivity activity, String resolution, X11SessionView.Listener listener) {}
        @Implementation protected boolean applyInputProfile(String selection) {
            liveSelection = selection;
            profileId = GameInputProfileChoices.profileIdOf(selection);
            return true;
        }
        @Implementation protected void setInputApis(boolean x, boolean d) {
            apiApplications++;
            xinput = x; dinput = d;
        }
        @Implementation protected int getActiveInputProfileId() { return profileId; }
        @Implementation protected List<X11SessionView.InputProfile> getInputControlsProfiles() {
            return Arrays.asList(ReflectionHelpers.callConstructor(X11SessionView.InputProfile.class,
                ReflectionHelpers.ClassParameter.from(int.class, 3),
                ReflectionHelpers.ClassParameter.from(String.class, "Test layout"),
                ReflectionHelpers.ClassParameter.from(boolean.class, false)));
        }
    }

    @Test public void drawerSwitchesApplyIndependentlyWhileOffAndMappingWaitsForNextLaunch() throws Exception {
        try (ActivityController<GameSessionActivity> host = newHost()) {
            Fixture fixture = new Fixture(host);
            Activity activity = host.get();
            assertTrue(fixture.toggle(R.id.game_controller_xinput_switch).isEnabled());
            assertEquals(0, fixture.shadow.profileId);
            fixture.toggle(R.id.game_controller_xinput_switch).performClick();
            fixture.flush();
            assertFalse(fixture.settings.read().isXinputEnabled());
            assertFalse(fixture.shadow.xinput);
            assertTrue(fixture.shadow.dinput);

            fixture.chooseMapper(1);
            assertEquals("dinput", fixture.settings.read().getInputProfileId());
            assertEquals("xinput", fixture.shadow.liveSelection);
            String summary = activity.getString(R.string.local_game_controller_mapping_state,
                activity.getString(R.string.local_game_controller_mapping_xinput));
            assertNotNull(find(fixture.page, summary));

            clickRow(fixture.page, "Test layout");
            fixture.flush();
            assertEquals("xinput:3", fixture.shadow.liveSelection);
            assertEquals("dinput:3", fixture.settings.read().getInputProfileId());
            fixture.recreatePanel();
            assertEquals("xinput:3", fixture.shadow.liveSelection);
            assertEquals("dinput:3", fixture.settings.read().getInputProfileId());
            clickRow(fixture.page, activity.getString(R.string.local_game_controller_off));
            fixture.flush();
            assertEquals("dinput", fixture.settings.read().getInputProfileId());
            assertEquals("xinput", fixture.shadow.liveSelection);
            fixture.panel.render();
            assertNotNull(find(fixture.page, summary));

            // A recreated panel reloads saved preferences, but still receives the frozen mapper.
            fixture.recreatePanel();
            assertFalse(fixture.toggle(R.id.game_controller_xinput_switch).isChecked());
            assertNotNull(find(fixture.page, summary));
            fixture.toggle(R.id.game_controller_dinput_switch).performClick();
            fixture.flush();
            assertFalse(fixture.shadow.dinput);
            assertFalse(fixture.shadow.xinput);
            assertEquals("dinput", fixture.settings.read().getInputProfileId());
        }
    }

    @Test public void failedSaveRestoresSwitchAndDoesNotApplyToSession() throws Exception {
        try (ActivityController<GameSessionActivity> host = newHost()) {
            Fixture fixture = new Fixture(host);
            Activity activity = host.get();
            File blocked = new File(fixture.directory, fixture.gameId + ".properties.tmp");
            assertTrue(blocked.mkdir());
            assertTrue(new File(blocked, "keep").createNewFile());
            fixture.toggle(R.id.game_controller_xinput_switch).performClick();
            fixture.flush();
            assertTrue(fixture.shadow.xinput);
            assertTrue(fixture.settings.read().isXinputEnabled());
            assertTrue(fixture.toggle(R.id.game_controller_xinput_switch).isChecked());
            assertEquals(View.VISIBLE, fixture.notice.getVisibility());
            fixture.chooseMapper(1);
            assertFalse(fixture.toggle(R.id.game_controller_mapping_switch).isChecked());
            assertEquals("xinput", fixture.settings.read().getInputProfileId());
            assertEquals("xinput", fixture.shadow.liveSelection);

            fixture.panel.settingsLoading(GameControllerPanel.Setting.LOAD);
            assertFalse(fixture.toggle(R.id.game_controller_xinput_switch).isEnabled());
            fixture.repository.delete(fixture.gameId);
            fixture.recreatePanel();
            assertFalse(fixture.toggle(R.id.game_controller_xinput_switch).isEnabled());
            assertEquals(View.VISIBLE, fixture.notice.getVisibility());
            assertFalse(fixture.repository.find(fixture.gameId).isPresent());
        }
    }

    @Test public void compactControlsFitNarrowPageAndMappingTogglesInlineInBothDirections() throws Exception {
        try (ActivityController<GameSessionActivity> host = newHost()) {
            Fixture fixture = new Fixture(host);
            SwitchMaterial xinput = fixture.toggle(R.id.game_controller_xinput_switch);
            SwitchMaterial dinput = fixture.toggle(R.id.game_controller_dinput_switch);
            assertSame(xinput.getParent(), dinput.getParent());
            LinearLayout row = (LinearLayout) xinput.getParent();
            assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
            int width = Math.round(280 * fixture.activity.getResources().getDisplayMetrics().density);
            fixture.page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            fixture.page.layout(0, 0, width, fixture.page.getMeasuredHeight());
            assertEquals(xinput.getMeasuredWidth(), dinput.getMeasuredWidth(), 1);
            assertEquals(xinput.getTop(), dinput.getTop());
            for (SwitchMaterial toggle : Arrays.asList(xinput, dinput)) {
                assertEquals(1, toggle.getLineCount());
                assertEquals(0, toggle.getLayout().getEllipsisCount(0));
                assertTrue("Complete label fits beside switch", toggle.getPaint().measureText(
                    toggle.getText().toString()) <= toggle.getWidth()
                    - toggle.getCompoundPaddingLeft() - toggle.getCompoundPaddingRight());
            }
            // Only actionable options and a short next-launch status remain, no introductory copy.
            ActivityGameSessionBinding binding = ReflectionHelpers.getField(fixture.activity, "binding");
            assertSame(fixture.page, binding.gameSessionPanelController.getChildAt(0));
            assertEquals(1, ((ViewGroup) find(fixture.page, fixture.activity.getString(
                R.string.local_game_pointer_lock_cursor)).getParent()).getChildCount());
            assertNotNull(find(fixture.page, fixture.activity.getString(
                R.string.local_game_controller_mapping_next_launch)));
            assertFalse(fixture.toggle(R.id.game_controller_mapping_switch).isChecked());
            fixture.chooseMapper(1);
            assertTrue(fixture.toggle(R.id.game_controller_mapping_switch).isChecked());
            assertEquals("dinput", fixture.settings.read().getInputProfileId());
            fixture.chooseMapper(0);
            assertFalse(fixture.toggle(R.id.game_controller_mapping_switch).isChecked());
            assertEquals("xinput", fixture.settings.read().getInputProfileId());
            assertEquals("xinput", fixture.shadow.liveSelection);
            assertNotNull(find(fixture.page, fixture.activity.getString(
                R.string.local_game_controller_mapping_next_launch)));
        }
    }

    @Test public void mappingSaveKeepsOtherControlsStableAndAllowsAnIndependentApiEdit() throws Exception {
        try (ActivityController<GameSessionActivity> host = newHost()) {
            Fixture fixture = new Fixture(host);
            SwitchMaterial xinput = fixture.toggle(R.id.game_controller_xinput_switch);
            SwitchMaterial dinput = fixture.toggle(R.id.game_controller_dinput_switch);
            SwitchMaterial mapping = fixture.toggle(R.id.game_controller_mapping_switch);
            View profileList = fixture.page.getChildAt(3);
            int apiApplications = fixture.shadow.apiApplications;
            try (IoPause mappingGate = new IoPause(fixture)) {
                mapping.performClick();
                assertSame(xinput, fixture.toggle(R.id.game_controller_xinput_switch));
                assertSame(dinput, fixture.toggle(R.id.game_controller_dinput_switch));
                assertSame(mapping, fixture.toggle(R.id.game_controller_mapping_switch));
                assertTrue(xinput.isEnabled());
                assertTrue(dinput.isEnabled());
                assertTrue(xinput.isChecked());
                assertTrue(dinput.isChecked());
                assertEquals(1f, xinput.getAlpha(), 0f);
                assertEquals(1f, dinput.getAlpha(), 0f);
                assertTrue(mapping.isChecked());
                assertFalse(mapping.isEnabled());
                // Hold the second write so the earlier mapping callback is observed separately.
                try (IoPause apiGate = new IoPause(fixture, false)) {
                    xinput.performClick();
                    assertFalse(xinput.isChecked());
                    assertFalse(xinput.isEnabled());
                    assertTrue(dinput.isEnabled());
                    assertTrue(mapping.isChecked());
                    mappingGate.close();
                    apiGate.awaitStarted();
                    Shadows.shadowOf(Looper.getMainLooper()).idle();
                    assertSame(xinput, fixture.toggle(R.id.game_controller_xinput_switch));
                    assertFalse(xinput.isChecked());
                    assertFalse(xinput.isEnabled());
                    assertTrue(mapping.isEnabled());
                    assertTrue(mapping.isChecked());
                    assertEquals(apiApplications, fixture.shadow.apiApplications);
                }
            }
            fixture.flush();
            assertSame(xinput, fixture.toggle(R.id.game_controller_xinput_switch));
            assertSame(dinput, fixture.toggle(R.id.game_controller_dinput_switch));
            assertSame(mapping, fixture.toggle(R.id.game_controller_mapping_switch));
            assertSame(profileList, fixture.page.getChildAt(3));
            assertTrue(xinput.isEnabled());
            assertTrue(dinput.isEnabled());
            assertTrue(mapping.isEnabled());
            assertFalse(xinput.isChecked());
            assertTrue(dinput.isChecked());
            assertTrue(mapping.isChecked());
            RuntimeProfile saved = fixture.settings.read();
            assertFalse(saved.isXinputEnabled());
            assertTrue(saved.isDinputEnabled());
            assertEquals("dinput", saved.getInputProfileId());
            assertFalse(fixture.shadow.xinput);
            assertTrue(fixture.shadow.dinput);
            assertEquals("xinput", fixture.shadow.liveSelection);
            assertEquals(apiApplications + 1, fixture.shadow.apiApplications);
            fixture.panel.render();
            assertSame(xinput, fixture.toggle(R.id.game_controller_xinput_switch));
            assertSame(dinput, fixture.toggle(R.id.game_controller_dinput_switch));
            assertSame(mapping, fixture.toggle(R.id.game_controller_mapping_switch));
            fixture.chooseMapper(0);
            assertSame(xinput, fixture.toggle(R.id.game_controller_xinput_switch));
            assertSame(dinput, fixture.toggle(R.id.game_controller_dinput_switch));
            assertSame(mapping, fixture.toggle(R.id.game_controller_mapping_switch));
            assertEquals(apiApplications + 1, fixture.shadow.apiApplications);
            assertEquals("xinput", fixture.settings.read().getInputProfileId());
        }
    }

    @Test public void mappingSaveFailureRestoresOnlyMappingInTheExistingControls() throws Exception {
        try (ActivityController<GameSessionActivity> host = newHost()) {
            Fixture fixture = new Fixture(host);
            File blocked = new File(fixture.directory, fixture.gameId + ".properties.tmp");
            assertTrue(blocked.mkdir());
            assertTrue(new File(blocked, "keep").createNewFile());
            SwitchMaterial xinput = fixture.toggle(R.id.game_controller_xinput_switch);
            SwitchMaterial dinput = fixture.toggle(R.id.game_controller_dinput_switch);
            SwitchMaterial mapping = fixture.toggle(R.id.game_controller_mapping_switch);
            int apiApplications = fixture.shadow.apiApplications;
            try (IoPause ignored = new IoPause(fixture)) {
                mapping.performClick();
                assertSame(xinput, fixture.toggle(R.id.game_controller_xinput_switch));
                assertSame(dinput, fixture.toggle(R.id.game_controller_dinput_switch));
                assertTrue(xinput.isEnabled());
                assertTrue(dinput.isEnabled());
                assertEquals(1f, xinput.getAlpha(), 0f);
                assertEquals(1f, dinput.getAlpha(), 0f);
            }
            fixture.flush();
            assertSame(xinput, fixture.toggle(R.id.game_controller_xinput_switch));
            assertSame(dinput, fixture.toggle(R.id.game_controller_dinput_switch));
            assertSame(mapping, fixture.toggle(R.id.game_controller_mapping_switch));
            assertTrue(xinput.isChecked());
            assertTrue(dinput.isChecked());
            assertTrue(mapping.isEnabled());
            assertFalse(mapping.isChecked());
            assertEquals("xinput", fixture.settings.read().getInputProfileId());
            assertEquals(apiApplications, fixture.shadow.apiApplications);
            assertEquals(View.VISIBLE, fixture.notice.getVisibility());
            assertEquals(fixture.activity.getString(R.string.local_game_controller_settings_failed),
                fixture.notice.getText().toString());
        }
    }

    private static final class IoPause implements AutoCloseable {
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch started = new CountDownLatch(1);
        IoPause(Fixture fixture) throws Exception {
            this(fixture, true);
        }
        IoPause(Fixture fixture, boolean awaitStarted) throws Exception {
            ExecutorService io = ReflectionHelpers.getField(fixture.host.get(), "ioExecutor");
            io.submit(() -> {
                started.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("IO pause expired");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            });
            if (awaitStarted) awaitStarted();
        }
        void awaitStarted() throws InterruptedException { assertTrue(started.await(5, TimeUnit.SECONDS)); }
        @Override public void close() { release.countDown(); }
    }

    private static ActivityController<GameSessionActivity> newHost() {
        String taskId = "launch-wine-controller-" + java.util.UUID.randomUUID();
        return Robolectric.buildActivity(GameSessionActivity.class, GameSessionActivity.createIntent(
            org.robolectric.RuntimeEnvironment.getApplication(), taskId));
    }

    private static final class Fixture {
        final ActivityController<GameSessionActivity> host;
        Activity activity;
        final String gameId;
        final File directory;
        final FileRuntimeProfileRepository repository;
        final GameControllerSettings settings;
        ShadowSession shadow;
        LinearLayout page;
        TextView notice;
        GameControllerPanel panel;

        Fixture(ActivityController<GameSessionActivity> host) throws Exception {
            this.host = host;
            activity = host.get();
            activity.setTheme(R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
            String taskId = activity.getIntent().getStringExtra(com.termux.ui.wine.api.LaunchTasks.EXTRA_TASK_ID);
            gameId = taskId.substring("launch-".length());
            GameStoragePaths paths = new GameStoragePaths(activity.getFilesDir());
            directory = paths.getProfilesDirectory();
            repository = new FileRuntimeProfileRepository(directory);
            settings = new GameControllerSettings(repository, gameId);
            RuntimeProfile profile = RuntimeProfilePresets.create(gameId, RuntimeProfilePreset.RECOMMENDED)
                .withInputProfileId("xinput").withInputApis(true, true);
            repository.save(profile);
            LaunchSpec spec = new LaunchSpec(taskId, gameId, "/games", "game.exe", ".",
                Collections.emptyList(), "/prefix", profile.getWinePackage(), profile.getGraphicsDriver(),
                profile.getDxWrapper(), profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(),
                "xinput", Collections.emptyMap(), "/events", "/log", "/lock", "/cancel", 300);
            File specDirectory = paths.getLaunchSpecsDirectory();
            assertTrue(specDirectory.isDirectory() || specDirectory.mkdirs());
            new LaunchSpecCodec().write(new File(specDirectory, taskId + ".launchspec"), spec);
            new FileLaunchTaskRepository(paths.getLaunchTasksDirectory()).save(
                LaunchTask.queued(taskId, gameId, System.currentTimeMillis()));
            host.create().start().resume().visible();
            bindViews();
            flush();
        }

        void bindViews() {
            activity = host.get();
            ActivityGameSessionBinding binding = ReflectionHelpers.getField(activity, "binding");
            assertNotNull(binding);
            shadow = Shadow.extract(binding.gameSessionX11);
            page = binding.gameSessionControllerList;
            notice = binding.gameSessionControllerNotice;
            panel = ReflectionHelpers.getField(activity, "controllerPanel");
        }

        void recreatePanel() throws Exception {
            flush();
            host.recreate();
            bindViews();
            flush();
        }

        void flush() throws Exception {
            ExecutorService io = ReflectionHelpers.getField(host.get(), "ioExecutor");
            io.submit(() -> {}).get(5, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }

        SwitchMaterial toggle(int id) {
            SwitchMaterial toggle = page.findViewById(id);
            assertNotNull(toggle);
            return toggle;
        }
        void chooseMapper(int which) throws Exception {
            SwitchMaterial toggle = toggle(R.id.game_controller_mapping_switch);
            if (toggle.isChecked() != (which == 1)) toggle.performClick();
            flush();
        }
    }

    private static void clickRow(View root, String text) {
        View label = find(root, text);
        if (label == null && text.equals("Test layout")) label = find(root, "Test layout · Landscape");
        assertNotNull(text, label);
        View target = label;
        while (!target.isClickable() && target.getParent() instanceof View) target = (View) target.getParent();
        assertTrue(target.performClick());
    }

    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = find(group.getChildAt(i), text);
                if (found != null) return found;
            }
        }
        return null;
    }
}
