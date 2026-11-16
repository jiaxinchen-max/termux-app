package com.termux.ui.desktop.activity;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DesktopInstallProgressViewTest {
    private static boolean animating(DesktopInstallProgressView view) throws Exception {
        Field field = DesktopInstallProgressView.class.getDeclaredField("animator");
        field.setAccessible(true);
        ValueAnimator animator = (ValueAnimator)field.get(view);
        return animator != null && animator.isStarted();
    }

    @Test public void installationOnlyAnimatesWhileVisibleAndHostActive() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.create().get();
            FrameLayout root = new FrameLayout(activity);
            DesktopInstallProgressView progress = new DesktopInstallProgressView(activity, null);
            root.addView(progress, new FrameLayout.LayoutParams(200, 3));
            activity.setContentView(root);
            controller.start().resume().visible();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            View decor = activity.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 400, 600);
            // Robolectric attaches the view with an initially hidden window.
            Object attachInfo = ReflectionHelpers.getField(progress, "mAttachInfo");
            ReflectionHelpers.setField(attachInfo, "mWindowVisibility", View.VISIBLE);
            progress.dispatchWindowVisibilityChanged(View.VISIBLE);
            progress.show();
            assertFalse(animating(progress));
            assertTrue("attached", progress.isAttachedToWindow());
            assertTrue("shown", progress.isShown());
            assertTrue("visible rect", progress.getGlobalVisibleRect(new android.graphics.Rect()));
            assertEquals("window visible", View.VISIBLE, progress.getWindowVisibility());
            assertTrue("system animation", ValueAnimator.areAnimatorsEnabled());
            progress.setHostActive(true);
            assertTrue(animating(progress));
            progress.hide();
            assertFalse(animating(progress));
            progress.show();
            assertTrue(animating(progress));
            progress.setHostActive(false);
            assertFalse(animating(progress));
            progress.setHostActive(true);
            root.setVisibility(View.GONE);
            assertFalse(animating(progress));
            root.setVisibility(View.VISIBLE);
            progress.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(animating(progress));
            progress.setTranslationY(1000);
            progress.getViewTreeObserver().dispatchOnPreDraw();
            assertFalse(animating(progress));
            progress.setTranslationY(0);
            progress.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(animating(progress));
            root.removeView(progress);
            assertFalse(animating(progress));
        }
    }

    @Test @Config(sdk = 25)
    public void disabledSystemAnimationsKeepVisibleStaticStatus() throws Exception {
        try (ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)) {
            Activity activity = controller.create().get();
            Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
            DesktopInstallProgressView progress = new DesktopInstallProgressView(activity, null);
            activity.setContentView(progress);
            controller.start().resume().visible();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            progress.setHostActive(true);
            progress.show();
            assertEquals(View.VISIBLE, progress.getVisibility());
            assertFalse(animating(progress));
        }
    }
}
