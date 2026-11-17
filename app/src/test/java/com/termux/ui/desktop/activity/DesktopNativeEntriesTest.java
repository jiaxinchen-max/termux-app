package com.termux.ui.desktop.activity;

import android.content.Context;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.GridLayout;
import android.widget.TextView;
import androidx.appcompat.widget.Toolbar;
import com.termux.ui.R;
import com.termux.ui.activity.FileManagerViewMode;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.api.DesktopTasks;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import com.termux.ui.desktop.domain.*;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowPopupMenu;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w840dp-h660dp-land")
public class DesktopNativeEntriesTest {
    private static final List<DesktopDe> EXPECTED = Arrays.asList(DesktopDe.XFCE4,
        DesktopDe.KWIN_PLASMA, DesktopDe.UBUNTU_GNOME, DesktopDe.HYPRLAND);

    private static void refresh(DesktopActivity activity) throws Exception {
        Method refresh = DesktopActivity.class.getDeclaredMethod("refresh");
        refresh.setAccessible(true);
        refresh.invoke(activity);
        drainRefresh(activity);
    }

    private static void drainRefresh(DesktopActivity activity) throws Exception {
        Field field = DesktopActivity.class.getDeclaredField("ioExecutor");
        field.setAccessible(true);
        ((ExecutorService)field.get(activity)).submit(() -> {}).get(10, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void selectNative(Context context, FileManagerViewMode layout) {
        layout.save(context, "desktop");
        context.getSharedPreferences("local_games_settings", 0).edit()
            .putBoolean("desktop_proot", false).commit();
    }

    private static void measure(DesktopActivity activity, int widthDp, int heightDp) {
        View root = activity.getWindow().getDecorView();
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        for (int pass = 0; pass < 3; pass++) {
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, width, height);
            // Let the next frame apply work deferred out of Android's active layout traversal.
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test public void emptyNativeCreatesFourStableEntriesWithoutInstallingAnything() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        selectNative(context, FileManagerViewMode.GRID);
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        assertTrue(new DesktopTasks(context).list().isEmpty());
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
            DesktopActivity activity = controller.create().start().resume().get();
            drainRefresh(activity);
            GridLayout grid = activity.findViewById(R.id.desktop_items);
            assertEquals("fresh Native must expose all four fixed entries", 4, grid.getChildCount());
            List<DesktopDistro> records = new DesktopTasks(context).list();
            assertEquals(4, records.size());
            Set<DesktopDe> types = new HashSet<>();
            for (DesktopDistro entry : records) {
                assertEquals(DesktopRunMode.NATIVE, entry.getRunMode());
                types.add(entry.getDe());
                assertEquals(DesktopSessionState.NOT_INSTALLED, new DesktopTasks(context).state(entry));
                assertFalse(paths.getDesktopSpecFile(entry.getId()).exists());
                assertFalse(paths.getDesktopStatusFile(entry.getId()).exists());
            }
            assertEquals(new HashSet<>(EXPECTED), types);
            assertNull(activity.findViewById(R.id.desktop_add_card));
            assertTrue(((TextView)activity.findViewById(R.id.desktop_count)).getText().toString().endsWith("4"));
            Map<String, byte[]> before = bytes(paths);
            View first = grid.getChildAt(0);
            refresh(activity);
            assertSame(first, grid.getChildAt(0));
            assertSameBytes(before, bytes(paths));
        }
    }

    private static Map<String, byte[]> bytes(GameStoragePaths paths) throws Exception {
        Map<String, byte[]> result = new TreeMap<>();
        for (DesktopDistro entry : new DesktopTasks(RuntimeEnvironment.getApplication()).list()) {
            String name = entry.getId() + ".properties";
            result.put(name, Files.readAllBytes(new File(paths.getDesktopDistrosDirectory(), name).toPath()));
        }
        return result;
    }

    private static void assertSameBytes(Map<String, byte[]> expected, Map<String, byte[]> actual) {
        assertEquals(expected.keySet(), actual.keySet());
        for (String key : expected.keySet()) assertArrayEquals(key, expected.get(key), actual.get(key));
    }

    @Test public void existingSettingsAndConflictingContainerIdSurviveInitializationAndRecreation() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        selectNative(context, FileManagerViewMode.GRID);
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        FileDesktopDistroRepository repository = new FileDesktopDistroRepository(paths.getDesktopDistrosDirectory());
        DesktopDistro custom = new DesktopDistro("my-xfce", "Custom XFCE", DesktopRunMode.NATIVE, "",
            DesktopDe.XFCE4, DesktopGlMode.ZINK, DesktopGuestGlMode.NONE, "1920x1080", 45, DesktopUsbStorageMode.READ_ONLY);
        DesktopDistro container = new DesktopDistro("native-hyprland", "My container", DesktopRunMode.PROOT,
            "ubuntu-xfce4-7", DesktopDe.XFCE4, DesktopGlMode.SYSTEM, DesktopGuestGlMode.DEFAULT,
            "1280x720", 30, DesktopUsbStorageMode.OFF);
        repository.save(custom); repository.save(container);
        Map<String, byte[]> before = bytes(paths);
        Map<String, byte[]> after;
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
            DesktopActivity activity = controller.create().start().resume().get();
            drainRefresh(activity);
            assertEquals(5, repository.list().size());
            assertEquals(4, ((GridLayout)activity.findViewById(R.id.desktop_items)).getChildCount());
            after = bytes(paths);
            for (String key : before.keySet()) assertArrayEquals(key, before.get(key), after.get(key));
            assertEquals(45, repository.find("my-xfce").get().getRefreshRate());
            refresh(activity);
            assertSameBytes(after, bytes(paths));
        }
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
            drainRefresh(controller.create().start().resume().get());
            assertSameBytes(after, bytes(paths));
        }
    }

    @Test public void nativeAndEmptyProotRemainVisibleAcrossLayoutsWidthsAndRepeatedSwitches() throws Exception {
        verifyLayouts();
    }

    @Test @Config(qualifiers = "w840dp-h660dp-land-night")
    public void nightNativeAndEmptyProotRemainVisibleAcrossLayoutsWidthsAndRepeatedSwitches() throws Exception {
        verifyLayouts();
    }

    private static void verifyLayouts() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        selectNative(context, FileManagerViewMode.GRID);
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
            DesktopActivity activity = controller.create().start().resume().get();
            drainRefresh(activity);
            Toolbar toolbar = activity.findViewById(R.id.desktop_toolbar);
            GridLayout grid = activity.findViewById(R.id.desktop_items);
            for (float fontScale : new float[]{1f, 2f}) {
                RuntimeEnvironment.setFontScale(fontScale);
                for (int width : new int[]{320, 840}) {
                    for (int layout = 0; layout < 2; layout++) {
                        for (int cycle = 0; cycle < 2; cycle++) {
                            measure(activity, width, width == 320 ? 660 : 420);
                            assertCardsHaveBounds(grid, 4);
                            for (int i = 0; i < 4; i++) {
                                View card = grid.getChildAt(i);
                                assertTrue(card.findViewById(R.id.desktop_primary).isEnabled());
                                card.findViewById(R.id.desktop_more).performClick();
                                android.widget.PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
                                for (int item = 0; item < popup.getMenu().size(); item++) {
                                    String title = popup.getMenu().getItem(item).getTitle().toString();
                                    assertNotEquals(activity.getString(R.string.desktop_action_delete), title);
                                    assertNotEquals(activity.getString(R.string.desktop_action_install), title);
                                }
                                popup.dismiss();
                            }
                            ((View)activity.findViewById(R.id.collection_runtime_icon).getParent()).performClick();
                            measure(activity, width, width == 320 ? 660 : 420);
                            assertCardsHaveBounds(grid, 2);
                            assertNotNull(activity.findViewById(R.id.desktop_add_card));
                            assertNotNull(activity.findViewById(R.id.desktop_import_card));
                            refresh(activity);
                            assertEquals(2, grid.getChildCount());
                            ((View)activity.findViewById(R.id.collection_runtime_icon).getParent()).performClick();
                            refresh(activity);
                        }
                        toolbar.getMenu().performIdentifierAction(R.id.local_games_action_view_mode, 0);
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setFontScale(1f);
        }
    }

    private static void assertCardsHaveBounds(GridLayout grid, int count) {
        assertEquals(count, grid.getChildCount());
        for (int index = 0; index < count; index++) {
            View card = grid.getChildAt(index);
            assertEquals(View.VISIBLE, card.getVisibility());
            assertTrue("card must have width", card.getWidth() > 0);
            assertTrue("card must have height", card.getHeight() > 0);
            assertTrue("card fits content horizontally", card.getLeft() >= 0 && card.getRight() <= grid.getWidth());
            assertTrue("card fits scroll content vertically", card.getTop() >= 0 && card.getBottom() <= grid.getHeight());
        }
    }

    @Test public void containersAndRetiredNativeRecordsStillOfferDeletion() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        for (DesktopRunMode mode : DesktopRunMode.values()) {
            selectNative(context, FileManagerViewMode.GRID);
            context.getSharedPreferences("local_games_settings", 0).edit()
                .putBoolean("desktop_proot", mode == DesktopRunMode.PROOT).commit();
            DesktopDistro entry = new DesktopDistro("deletable", "Existing", mode,
                mode == DesktopRunMode.PROOT ? "ubuntu-xfce4-1" : "",
                mode == DesktopRunMode.PROOT ? DesktopDe.XFCE4 : DesktopDe.GNOME,
                DesktopGlMode.SYSTEM, mode == DesktopRunMode.PROOT ? DesktopGuestGlMode.DEFAULT
                    : DesktopGuestGlMode.NONE, "1280x720", 60, DesktopUsbStorageMode.OFF);
            try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
                DesktopActivity activity = controller.create().get();
                Method render = DesktopActivity.class.getDeclaredMethod("render", List.class, List.class);
                render.setAccessible(true);
                render.invoke(activity, Collections.singletonList(entry),
                    Collections.singletonList(DesktopSessionState.NOT_INSTALLED));
                activity.findViewById(R.id.desktop_more).performClick();
                android.widget.PopupMenu popup = ShadowPopupMenu.getLatestPopupMenu();
                boolean deletion = false;
                for (int item = 0; item < popup.getMenu().size(); item++) {
                    String title = popup.getMenu().getItem(item).getTitle().toString();
                    deletion |= title.equals(activity.getString(R.string.desktop_action_delete));
                    assertNotEquals(activity.getString(R.string.desktop_action_install), title);
                }
                assertTrue("existing deletable records retain cleanup", deletion);
                popup.dismiss();
            }
        }
    }

    @Test public void emptyProotAlwaysOffersVisibleAddAndImportCards() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        selectNative(context, FileManagerViewMode.GRID);
        context.getSharedPreferences("local_games_settings", 0).edit().putBoolean("desktop_proot", true).commit();
        try (ActivityController<DesktopActivity> controller = Robolectric.buildActivity(DesktopActivity.class)) {
            DesktopActivity activity = controller.create().get();
            Method render = DesktopActivity.class.getDeclaredMethod("render", List.class, List.class);
            render.setAccessible(true);
            render.invoke(activity, Collections.emptyList(), Collections.emptyList());
            measure(activity, 840, 660);
            View add = activity.findViewById(R.id.desktop_add_card);
            assertNotNull(add);
            assertTrue(add.getWidth() > 0);
            assertTrue(add.getHeight() > 0);
            assertTrue(add.getGlobalVisibleRect(new Rect()));
            assertCardsHaveBounds(activity.findViewById(R.id.desktop_items), 2);
        }
    }
}
