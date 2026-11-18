package com.termux.ui.activity;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import com.termux.ui.R;
import com.termux.ui.wine.domain.GameRuntimeBackendType;
import com.termux.ui.wine.importer.GameImportDrafts;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w840dp-h660dp-land")
public class GameFileDetailsTest {
    private File folder;

    @Before public void setup() throws Exception {
        folder = new File(RuntimeEnvironment.getApplication().getFilesDir(), "details-tests");
        assertTrue(folder.mkdirs());
    }

    private File write(String name, byte[] bytes) throws Exception {
        File file = new File(folder, name);
        Files.write(file.toPath(), bytes);
        return file;
    }

    private byte[] program(boolean plus, int flags, int subsystem) {
        byte[] bytes = new byte[1024];
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort(0, (short)0x5a4d).putInt(0x3c, 128).putInt(128, 0x4550);
        b.putShort(132, (short)(plus ? 0x8664 : 0x14c)).putShort(134, (short)1);
        b.putShort(148, (short)(plus ? 240 : 224)).putShort(150, (short)flags);
        b.putShort(152, (short)(plus ? 0x20b : 0x10b));
        b.putInt(168, 0x1000).putInt(184, 0x1000).putInt(188, 512);
        b.putInt(208, 0x2000).putInt(212, 512).putShort(220, (short)subsystem);
        return bytes;
    }

    private ActivityController<GameFileManagerActivity> open(GameRuntimeBackendType backend) throws Exception {
        Intent intent = new Intent(RuntimeEnvironment.getApplication(), GameFileManagerActivity.class)
            .putExtra(GameFileManagerActivity.EXTRA_IMPORT_BACKEND, backend.getStorageValue());
        ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class, intent)
            .create().start().resume().visible();
        browse(c.get(), folder);
        return c;
    }

    private void browse(GameFileManagerActivity a, File directory) throws Exception {
        Method m = GameFileManagerActivity.class.getDeclaredMethod("showLocal", File.class);
        m.setAccessible(true); m.invoke(a, directory);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private Object field(GameFileManagerActivity a, String name) throws Exception {
        Field f = GameFileManagerActivity.class.getDeclaredField(name); f.setAccessible(true);
        return f.get(a);
    }

    private ExecutorService worker(GameFileManagerActivity a) throws Exception {
        return (ExecutorService)field(a, "ioExecutor");
    }

    private void flush(GameFileManagerActivity a) throws Exception {
        for (int i=0; i<2; i++) {
            worker(a).submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    private View text(View view, String text) {
        if (view instanceof TextView && text.contentEquals(((TextView)view).getText())) return view;
        if (view instanceof ViewGroup) for (int i=0; i<((ViewGroup)view).getChildCount(); i++) {
            View found = text(((ViewGroup)view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }

    private View entry(GameFileManagerActivity a, String name) {
        GridLayout grid = a.findViewById(R.id.local_game_file_manager_items);
        for (int i=0; i<grid.getChildCount(); i++)
            if (text(grid.getChildAt(i), name) != null) return grid.getChildAt(i);
        throw new AssertionError(name);
    }

    private AlertDialog details() { return (AlertDialog)ShadowDialog.getLatestDialog(); }
    private PopupMenu menu(GameFileManagerActivity a) throws Exception { return (PopupMenu)field(a, "activeFileMenu"); }

    @Test public void ordinaryFilesOnlyShowInformationAndNeverOfferImportInEitherEntry() throws Exception {
        write("data.archive", new byte[]{'P','K',3,4});
        write("notes.txt", "notes".getBytes("UTF-8"));
        write("fake.exe", "not an executable".getBytes("UTF-8"));
        write("library.dll", program(true, 0x2002, 2));
        write("driver.sys", program(true, 0x1002, 1));
        try (ActivityController<GameFileManagerActivity> c = open(GameRuntimeBackendType.ROOTFS_PROOT)) {
            GameFileManagerActivity a = c.get();
            for (String name : new String[]{"data.archive","notes.txt","fake.exe","library.dll","driver.sys"}) {
                entry(a, name).performClick(); flush(a);
                AlertDialog d = details(); assertTrue(d.isShowing());
                assertNotNull(text(d.getWindow().getDecorView(), name));
                String message = ((TextView)d.findViewById(android.R.id.message)).getText().toString();
                assertTrue(message.contains("Size:")); assertTrue(message.contains("Modified:"));
                assertFalse(message.contains("Windows")); assertFalse(message.contains("C:"));
                assertEquals(View.GONE, d.getButton(DialogInterface.BUTTON_POSITIVE).getVisibility());
                d.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
                entry(a, name).performLongClick(); flush(a);
                assertEquals(101, menu(a).getMenu().getItem(0).getItemId());
                assertNull(menu(a).getMenu().findItem(106)); menu(a).dismiss();
                assertNull(Shadows.shadowOf(a).getNextStartedActivity());
            }
            assertTrue(new GameImportDrafts(a.getFilesDir()).games().list().isEmpty());
        }
    }

    @Test public void validPe32AndPe32PlusIncludingRenamedFilesOfferNativeImport() throws Exception {
        checkPrograms(GameRuntimeBackendType.GLIBC_TERMUX_BOX, R.string.local_game_import_native_exe);
    }

    @Test public void validProgramsOfferProotImportAndOnlyTheButtonCreatesADraft() throws Exception {
        checkPrograms(GameRuntimeBackendType.ROOTFS_PROOT, R.string.local_game_import_proot_exe);
    }

    private void checkPrograms(GameRuntimeBackendType backend, int label) throws Exception {
        write("program.exe", program(false, 2, 2));
        write("renamed.data", program(true, 0x22, 3));
        try (ActivityController<GameFileManagerActivity> c = open(backend)) {
            GameFileManagerActivity a = c.get();
            for (String name : new String[]{"program.exe", "renamed.data"}) {
                entry(a, name).performClick(); flush(a);
                AlertDialog d = details();
                assertEquals(View.VISIBLE, d.getButton(DialogInterface.BUTTON_POSITIVE).getVisibility());
                assertEquals(a.getString(label), d.getButton(DialogInterface.BUTTON_POSITIVE).getText());
                assertTrue(((TextView)d.findViewById(android.R.id.message)).getText().toString().contains("C:"));
                assertNull(Shadows.shadowOf(a).getNextStartedActivity());
                assertTrue(new GameImportDrafts(a.getFilesDir()).games().list().isEmpty());
                d.dismiss(); entry(a, name).performLongClick(); flush(a);
                assertEquals(a.getString(label), menu(a).getMenu().findItem(106).getTitle());
                assertEquals(101, menu(a).getMenu().getItem(0).getItemId()); menu(a).dismiss();
            }
            if (backend == GameRuntimeBackendType.GLIBC_TERMUX_BOX) {
                entry(a, "program.exe").performLongClick(); flush(a);
                menu(a).getMenu().performIdentifierAction(106, 0);
            } else {
                entry(a, "program.exe").performClick(); flush(a);
                details().getButton(DialogInterface.BUTTON_POSITIVE).performClick();
            }
            flush(a);
            assertEquals(1, new GameImportDrafts(a.getFilesDir()).games().list().size());
            assertNotNull(Shadows.shadowOf(a).getNextStartedActivity());
        }
    }

    @Test public void replacedProgramIsRejectedBeforeDraftCreation() throws Exception {
        File file = write("program.exe", program(true, 2, 2));
        try (ActivityController<GameFileManagerActivity> c = open(GameRuntimeBackendType.ROOTFS_PROOT)) {
            GameFileManagerActivity a = c.get(); entry(a, file.getName()).performClick(); flush(a);
            assertEquals(View.VISIBLE, details().getButton(DialogInterface.BUTTON_POSITIVE).getVisibility());
            Files.write(file.toPath(), "replaced".getBytes("UTF-8"));
            details().getButton(DialogInterface.BUTTON_POSITIVE).performClick(); flush(a);
            assertTrue(new GameImportDrafts(a.getFilesDir()).games().list().isEmpty());
            assertNull(Shadows.shadowOf(a).getNextStartedActivity());
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains(a.getString(R.string.browser_not_windows_program)));
        }
    }

    @Test public void delayedInspectionCannotChangeANewerDetailsDialogOrItsMenu() throws Exception {
        write("program.exe", program(true, 2, 2)); write("notes.txt", new byte[]{1});
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ActivityController<GameFileManagerActivity> c = open(GameRuntimeBackendType.ROOTFS_PROOT)) {
            GameFileManagerActivity a = c.get();
            worker(a).execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            entry(a, "program.exe").performClick(); AlertDialog old = details();
            assertEquals(View.GONE, old.getButton(DialogInterface.BUTTON_POSITIVE).getVisibility());
            entry(a, "notes.txt").performClick(); AlertDialog current = details();
            assertFalse(old.isShowing()); release.countDown(); flush(a);
            assertTrue(current.isShowing());
            assertEquals(View.GONE, current.getButton(DialogInterface.BUTTON_POSITIVE).getVisibility());
            current.dismiss(); entry(a, "program.exe").performLongClick();
            PopupMenu previous = menu(a);
            entry(a, "notes.txt").performLongClick(); flush(a);
            assertNotSame(previous, menu(a)); assertNull(menu(a).getMenu().findItem(106));
        } finally { release.countDown(); }
    }

    @Test public void navigationDismissesDetailsAndPendingInspectionDoesNotReopenIt() throws Exception {
        write("program.exe", program(true, 2, 2));
        File child = new File(folder, "child"); assertTrue(child.mkdir());
        try (ActivityController<GameFileManagerActivity> c = open(GameRuntimeBackendType.ROOTFS_PROOT)) {
            GameFileManagerActivity a = c.get(); entry(a, "program.exe").performClick();
            AlertDialog d = details(); browse(a, child); flush(a);
            assertFalse(d.isShowing()); assertNull(field(a, "activeFileDetails"));
        }
    }

    @Test @Config(qualifiers = "w400dp-h800dp-port-night")
    public void informationAndMenuRulesAlsoHoldInDarkPortraitList() throws Exception {
        FileManagerViewMode.LIST.save(RuntimeEnvironment.getApplication());
        ordinaryFilesOnlyShowInformationAndNeverOfferImportInEitherEntry();
    }

    @Test public void selectionClickOnlySelectsAndDirectoryClickStillNavigates() throws Exception {
        write("program.exe", program(true, 2, 2));
        File child = new File(folder, "child"); assertTrue(child.mkdir());
        try (ActivityController<GameFileManagerActivity> c = open(GameRuntimeBackendType.ROOTFS_PROOT)) {
            GameFileManagerActivity a = c.get();
            entry(a, "child").performClick(); assertEquals(child.getCanonicalFile(), field(a, "localCurrent"));
            browse(a, folder);
            ((Toolbar)a.findViewById(R.id.local_game_file_manager_toolbar)).getMenu().performIdentifierAction(R.id.browser_select, 0);
            entry(a, "program.exe").performClick(); flush(a);
            assertNull(ShadowDialog.getLatestDialog());
            assertEquals(a.getString(R.string.browser_selected_count, 1), ((Toolbar)a.findViewById(R.id.local_game_file_manager_toolbar)).getTitle());
            assertTrue(new GameImportDrafts(a.getFilesDir()).games().list().isEmpty());
        }
    }

    @Test public void actualPopupAndDialogUseTheReadableBrowserSurfaceWithoutDimmingText() throws Exception {
        write("notes.txt", new byte[]{1});
        try (ActivityController<GameFileManagerActivity> c = open(GameRuntimeBackendType.ROOTFS_PROOT)) {
            GameFileManagerActivity a = c.get(); entry(a, "notes.txt").performLongClick(); flush(a);
            android.widget.PopupWindow popup = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getLatestPopupWindow();
            assertNotNull(popup); assertTrue(popup.isShowing());
            android.graphics.drawable.GradientDrawable popupSurface =
                (android.graphics.drawable.GradientDrawable)popup.getBackground();
            int popupColor = popupSurface.getColor().getDefaultColor();
            assertEquals(217, android.graphics.Color.alpha(popupColor));
            assertEquals(1f, popup.getContentView().getAlpha(), 0f);
            menu(a).dismiss(); entry(a, "notes.txt").performClick(); flush(a);
            android.graphics.drawable.GradientDrawable dialogSurface =
                (android.graphics.drawable.GradientDrawable)details().getWindow().getDecorView().getBackground();
            assertEquals(popupColor, dialogSurface.getColor().getDefaultColor());
            assertEquals(1f, details().findViewById(android.R.id.message).getAlpha(), 0f);
            assertEquals(androidx.core.content.ContextCompat.getColor(a, R.color.local_games_dialog_surface), popupColor);
            AlertDialog ordinary = new FrostedDialogBuilder(a).setMessage("Confirmation").show();
            try {
                android.graphics.drawable.GradientDrawable ordinarySurface =
                    (android.graphics.drawable.GradientDrawable)ordinary.getWindow().getDecorView().getBackground();
                assertEquals(popupColor, ordinarySurface.getColor().getDefaultColor());
                assertEquals(1f, ordinary.findViewById(android.R.id.message).getAlpha(), 0f);
            } finally { ordinary.dismiss(); }
        }
    }

    @Test @Config(sdk = 31, qualifiers = "w400dp-h800dp-port-night")
    public void actualBrowserSurfacesAlsoApplyToDarkModernWindows() throws Exception {
        actualPopupAndDialogUseTheReadableBrowserSurfaceWithoutDimmingText();
    }
}
