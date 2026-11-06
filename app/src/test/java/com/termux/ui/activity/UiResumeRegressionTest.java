package com.termux.ui.activity;

import android.widget.FrameLayout;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import com.termux.ui.R;
import com.termux.ui.databinding.ActivityLocalGameFileManagerBinding;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class UiResumeRegressionTest {
    @Test @Config(sdk = 28, qualifiers = "night")
    public void darkNavigationRestoresLightIconsWhenWindowResumes() {
        try (ActivityController<AppCompatActivity> controller =
                 Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
            controller.create();
            activity.setContentView(new FrameLayout(activity));
            UiNavigation.install(activity, R.id.local_games_navigation_library, null);
            controller.start().resume().visible().pause();
            View decor = activity.getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
            controller.resume();
            assertEquals(0, decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            assertEquals(0, decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    @Test public void terminalNavigationResumesAfterHostConsumesLaunchIntent() {
        try (ActivityController<AppCompatActivity> controller =
                 Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
            controller.create();
            activity.setContentView(new FrameLayout(activity));
            UiNavigation.install(activity, R.id.local_games_navigation_terminal, null);
            activity.setIntent(null);
            controller.start().resume().visible();
            controller.pause().resume();
            assertNotNull(activity.findViewById(R.id.local_games_navigation));
        }
    }

    @Test @Config(qualifiers = "w840dp-h660dp-land")
    public void landscapeFileBrowserHasRequiredScrollContainer() {
        try (ActivityController<AppCompatActivity> controller =
                 Robolectric.buildActivity(AppCompatActivity.class)) {
            AppCompatActivity activity = controller.get();
            activity.setTheme(R.style.Theme_TermuxLocalGames_DayNight_NoActionBar);
            controller.setup();
            ActivityLocalGameFileManagerBinding binding =
                ActivityLocalGameFileManagerBinding.inflate(activity.getLayoutInflater());
            assertNotNull(binding.localGameFileManagerScroll);
            assertSame(binding.localGameFileManagerScroll, binding.localGameFileManagerItems.getParent());
        }
    }
}
