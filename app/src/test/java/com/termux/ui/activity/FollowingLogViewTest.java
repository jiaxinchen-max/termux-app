package com.termux.ui.activity;

import android.app.Activity;
import android.view.MotionEvent;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class FollowingLogViewTest {
    @Test
    public void followsAfterLayoutAndResizeButPreservesUserScrollback() {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            FollowingLogView view = new FollowingLogView(controller.get());
            controller.get().setContentView(view);
            view.setOutput(lines(100));
            layout(view, 200);
            assertAtBottom(view);

            layout(view, 100);
            assertAtBottom(view);

            touch(view, MotionEvent.ACTION_DOWN);
            view.scrollTo(0, 0);
            touch(view, MotionEvent.ACTION_UP);
            view.setOutput(lines(200));
            layout(view, 100);
            assertEquals(0, view.getScrollY());

            touch(view, MotionEvent.ACTION_DOWN);
            view.scrollTo(0, view.getChildAt(0).getHeight());
            touch(view, MotionEvent.ACTION_UP);
            view.setOutput(lines(300));
            layout(view, 100);
            assertAtBottom(view);
        }
    }

    private static String lines(int count) {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < count; index++) text.append("line ").append(index).append('\n');
        return text.toString();
    }

    private static void layout(FollowingLogView view, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 400, height);
        ShadowLooper.idleMainLooper();
    }

    private static void assertAtBottom(FollowingLogView view) {
        assertTrue(view.getScrollY() > 0);
        assertFalse(view.canScrollVertically(1));
    }

    private static void touch(FollowingLogView view, int action) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, 200, 50, 0);
        view.dispatchTouchEvent(event);
        event.recycle();
    }
}
