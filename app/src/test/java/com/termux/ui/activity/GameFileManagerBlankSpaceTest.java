package com.termux.ui.activity;

import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.GridLayout;
import android.widget.ScrollView;
import androidx.appcompat.widget.PopupMenu;
import com.termux.ui.R;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Sends touch events through the actual ScrollView instead of invoking performLongClick. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk=28, qualifiers="w840dp-h660dp-land")
@LooperMode(LooperMode.Mode.PAUSED)
public class GameFileManagerBlankSpaceTest {
    private File folder;
    @Before public void setup() {
        folder = new File(RuntimeEnvironment.getApplication().getFilesDir(), "blank-press-tests");
        assertTrue(folder.mkdirs());
    }
    private void browse(GameFileManagerActivity activity, File path) throws Exception {
        Method method = activity.getClass().getDeclaredMethod("showLocal", File.class);
        method.setAccessible(true); method.invoke(activity, path);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }
    private PopupMenu popup(GameFileManagerActivity activity) throws Exception {
        Field field = activity.getClass().getDeclaredField("activeFileMenu"); field.setAccessible(true);
        return (PopupMenu) field.get(activity);
    }
    private void event(View view, long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private void longPress(View view, float x, float y) {
        long down = SystemClock.uptimeMillis(); event(view, down, MotionEvent.ACTION_DOWN, x, y);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(
            ViewConfiguration.getTapTimeout() + ViewConfiguration.getLongPressTimeout() + 100));
        event(view, down, MotionEvent.ACTION_UP, x, y);
    }
    @Test public void viewportPaddingLongPressHasNoMenuInLandscape() throws Exception {
        try (ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity a = c.get(); browse(a, folder);
            ScrollView scroll = a.findViewById(R.id.local_game_file_manager_scroll);
            assertTrue(scroll.getHeight() > 50); assertTrue(scroll.getPaddingBottom() > 0);
            longPress(scroll, scroll.getWidth()/2f, scroll.getHeight()-1);
            assertNull(popup(a));
        }
    }
    @Test @Config(qualifiers="w400dp-h800dp-port")
    public void viewportPaddingLongPressHasNoMenuInPortraitList() throws Exception {
        FileManagerViewMode.LIST.save(RuntimeEnvironment.getApplication());
        viewportPaddingLongPressHasNoMenuInLandscape();
    }
    @Test public void blankGridBelowEmptyMessageHasNoMenu() throws Exception {
        try (ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity a = c.get(); browse(a, folder);
            ScrollView scroll = a.findViewById(R.id.local_game_file_manager_scroll);
            longPress(scroll, scroll.getWidth()/2f, scroll.getHeight()/2f);
            assertNull(popup(a));
        }
    }
    @Test public void draggingBlankSpaceDoesNotOpenAMenu() throws Exception {
        try (ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity a = c.get(); browse(a, folder);
            ScrollView scroll = a.findViewById(R.id.local_game_file_manager_scroll);
            long down = SystemClock.uptimeMillis(); float x = scroll.getWidth()/2f;
            event(scroll, down, MotionEvent.ACTION_DOWN, x, scroll.getHeight()-1);
            event(scroll, down, MotionEvent.ACTION_MOVE, x, scroll.getHeight()/2f);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            event(scroll, down, MotionEvent.ACTION_UP, x, scroll.getHeight()/2f);
            assertNull(popup(a));
        }
    }
    @Test public void cancelledBlankPressDoesNotOpenAMenu() throws Exception {
        try (ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity a = c.get(); browse(a, folder);
            ScrollView scroll = a.findViewById(R.id.local_game_file_manager_scroll);
            long down = SystemClock.uptimeMillis(); float x = scroll.getWidth()/2f, y = scroll.getHeight()-1;
            event(scroll, down, MotionEvent.ACTION_DOWN, x, y);
            event(scroll, down, MotionEvent.ACTION_CANCEL, x, y);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
            assertNull(popup(a));
        }
    }
    @Test public void touchingAFileDoesNotOpenTheCurrentDirectoryMenu() throws Exception {
        File file = new File(folder, "item.txt"); assertTrue(file.createNewFile());
        try (ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity a = c.get(); browse(a, folder);
            ScrollView scroll = a.findViewById(R.id.local_game_file_manager_scroll);
            GridLayout grid = a.findViewById(R.id.local_game_file_manager_items); View item = grid.getChildAt(0);
            longPress(scroll, grid.getLeft()+item.getLeft()+item.getWidth()/2f,
                grid.getTop()+item.getTop()+item.getHeight()/2f-scroll.getScrollY());
            assertNotNull(popup(a)); assertEquals(file.getName(), popup(a).getMenu().getItem(0).getTitle());
            assertNotNull(popup(a).getMenu().findItem(106));
        }
    }
    @Test public void rootBlankLongPressHasNoMenuEvenWithPendingClipboard() throws Exception {
        try (ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity a = c.get(); browse(a, a.getFilesDir());
            ScrollView scroll = a.findViewById(R.id.local_game_file_manager_scroll);
            longPress(scroll, scroll.getWidth()/2f, scroll.getHeight()-1); assertNull(popup(a));
            Field clipboard = a.getClass().getDeclaredField("clipboardSource"); clipboard.setAccessible(true); clipboard.set(a, folder);
            longPress(scroll, scroll.getWidth()/2f, scroll.getHeight()-1);
            assertNull(popup(a));
        }
    }

}
