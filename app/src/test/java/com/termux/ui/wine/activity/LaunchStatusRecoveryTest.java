package com.termux.ui.wine.activity;

import android.view.View;
import android.widget.TextView;
import com.termux.ui.R;
import com.termux.ui.wine.domain.LaunchStage;
import com.termux.ui.wine.domain.LaunchTask;
import com.termux.ui.wine.domain.LaunchTaskState;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LaunchStatusRecoveryTest {
    private void renderAfterSessionOpened(GameLaunchActivity activity, LaunchTaskState state) throws Exception {
        Field opened = GameLaunchActivity.class.getDeclaredField("sessionOpened");
        opened.setAccessible(true);
        opened.setBoolean(activity, true);
        Method render = GameLaunchActivity.class.getDeclaredMethod("render", LaunchTask.class);
        render.setAccessible(true);
        render.invoke(activity, new LaunchTask("launch-test", "configuration-test", state,
            LaunchStage.COMPLETE, 100, 1, 123, null,
            state == LaunchTaskState.FAILED ? "host_process_lost" : "",
            state == LaunchTaskState.FAILED, ""));
    }

    @Test public void lostProcessShowsFailureAndRetryAfterSessionWasOpened() throws Exception {
        try (ActivityController<GameLaunchActivity> controller = Robolectric.buildActivity(GameLaunchActivity.class)) {
            GameLaunchActivity activity = controller.create().get();
            renderAfterSessionOpened(activity, LaunchTaskState.FAILED);
            TextView stage = activity.findViewById(R.id.game_launch_stage);
            TextView action = activity.findViewById(R.id.game_launch_action);
            assertTrue(stage.getText().toString().contains("host_process_lost"));
            assertEquals(View.VISIBLE, action.getVisibility());
            assertEquals(activity.getString(R.string.local_game_launch_retry), action.getText().toString());
        }
    }

    @Test public void cancelledSessionShowsExitedInsteadOfOldRunningProgress() throws Exception {
        try (ActivityController<GameLaunchActivity> controller = Robolectric.buildActivity(GameLaunchActivity.class)) {
            GameLaunchActivity activity = controller.create().get();
            renderAfterSessionOpened(activity, LaunchTaskState.CANCELLED);
            TextView state = activity.findViewById(R.id.game_launch_state);
            TextView progress = activity.findViewById(R.id.game_launch_progress_value);
            assertEquals(activity.getString(R.string.local_game_launch_phase_cancelled), state.getText().toString());
            assertEquals("100%", progress.getText().toString());
        }
    }
}
