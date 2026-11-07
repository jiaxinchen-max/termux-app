package com.termux.ui.activity;

import android.content.Context;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.termux.ui.R;
import com.termux.ui.api.LocalGames;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.concurrent.atomic.AtomicReference;

/** Window-manager regression checks; no real preparation task is created or cancelled. */
@RunWith(AndroidJUnit4.class)
public class RuntimeSetupConsoleLifecycleTest {
    private RuntimeSetupConsoleDialog show(LocalGamesActivity activity) {
        return RuntimeSetupConsoleDialog.show(activity, "lifecycle-test",
            R.string.local_games_runtime_setup_console_title,
            taskId -> new RuntimeSetupConsoleDialog.TaskStatusLookup.Status(
                true, false, false, "test-failure-log"));
    }

    private ActivityScenario<LocalGamesActivity> launch() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        return ActivityScenario.launch(LocalGames.createLaunchIntent(context));
    }

    @Test public void repeatedDismissDoesNotRemoveWindowTwice() {
        try (ActivityScenario<LocalGamesActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                RuntimeSetupConsoleDialog dialog = show(activity);
                dialog.dismiss();
                dialog.dismiss();
            });
        }
    }

    @Test public void completionAfterWindowManagerDetachIsSafe() {
        try (ActivityScenario<LocalGamesActivity> scenario = launch()) {
            scenario.onActivity(activity -> {
                RuntimeSetupConsoleDialog dialog = show(activity);
                dialog.getWindow().getWindowManager().removeViewImmediate(
                    dialog.getWindow().getDecorView());
                dialog.dismiss();
                dialog.dismiss();
            });
        }
    }

    @Test public void completionAfterActivityRecreationIsSafe() {
        AtomicReference<RuntimeSetupConsoleDialog> old = new AtomicReference<>();
        try (ActivityScenario<LocalGamesActivity> scenario = launch()) {
            scenario.onActivity(activity -> old.set(show(activity)));
            scenario.recreate();
            scenario.onActivity(activity -> {
                old.get().dismiss();
                RuntimeSetupConsoleDialog current = show(activity);
                current.dismiss();
            });
        }
    }
}
