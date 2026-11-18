package com.termux.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.GridLayout;
import androidx.appcompat.widget.PopupMenu;
import androidx.appcompat.widget.Toolbar;
import androidx.appcompat.app.AlertDialog;
import com.termux.ui.R;
import com.termux.ui.data.GameStoragePaths;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w840dp-h660dp-land")
public class GameFileManagerOperationsTest {
    private File folder;
    @Before public void setup() throws Exception {
        org.robolectric.Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton()).addExtensionMimeTypMapping("txt", "text/plain");
        folder = new File(RuntimeEnvironment.getApplication().getFilesDir(), "browser-tests");
        assertTrue(folder.mkdirs());
    }
    private File write(String path, String value) throws Exception {
        File file = new File(folder, path); file.getParentFile().mkdirs();
        Files.write(file.toPath(), value.getBytes("UTF-8")); return file;
    }
    private ActivityController<GameFileManagerActivity> open() throws Exception {
        ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class)
            .create().start().resume().visible();
        browse(c.get(), folder); return c;
    }
    private void browse(GameFileManagerActivity a, File directory) throws Exception {
        Method m = a.getClass().getDeclaredMethod("showLocal", File.class); m.setAccessible(true); m.invoke(a, directory);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    private PopupMenu popup(GameFileManagerActivity a) throws Exception { return (PopupMenu) field(a, "activeFileMenu"); }
    private Toolbar toolbar(GameFileManagerActivity a) { return a.findViewById(R.id.local_game_file_manager_toolbar); }
    private View text(View parent, String label) {
        if (parent instanceof TextView && label.contentEquals(((TextView) parent).getText())) return parent;
        if (parent instanceof ViewGroup) for (int i=0;i<((ViewGroup)parent).getChildCount();i++) {
            View result = text(((ViewGroup)parent).getChildAt(i), label); if (result != null) return result;
        }
        return null;
    }
    private View entry(GameFileManagerActivity a, String label) {
        GridLayout grid = a.findViewById(R.id.local_game_file_manager_items);
        for (int i=0;i<grid.getChildCount();i++) if (text(grid.getChildAt(i), label) != null) return grid.getChildAt(i);
        throw new AssertionError("No browser entry " + label);
    }
    private EditText nameInput(View view) {
        if (view instanceof EditText) return (EditText)view;
        if (view instanceof ViewGroup) for (int i=0;i<((ViewGroup)view).getChildCount();i++) {
            EditText input = nameInput(((ViewGroup)view).getChildAt(i));
            if (input != null) return input;
        }
        return null;
    }
    private AlertDialog createDialog(GameFileManagerActivity a, boolean directory) {
        assertTrue(toolbar(a).getMenu().performIdentifierAction(directory ? R.id.browser_new_folder : R.id.browser_new_file, 0));
        AlertDialog dialog = (AlertDialog)ShadowDialog.getLatestDialog();
        assertNotNull(dialog); assertTrue(dialog.isShowing());
        assertNotNull(nameInput(dialog.getWindow().getDecorView()));
        return dialog;
    }
    private void submitName(AlertDialog dialog, String name) {
        nameInput(dialog.getWindow().getDecorView()).setText(name);
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
    }
    private void flush(GameFileManagerActivity a) throws Exception {
        for (int i=0;i<2;i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ((ExecutorService)field(a,"ioExecutor")).submit(() -> {}).get(10, TimeUnit.SECONDS);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }

    @Test public void multipleFilesCopyAndMoveThroughTheToolbarAndAcrossDirectories() throws Exception {
        write("one.txt", "one"); write("two.txt", "two");
        File target = new File(folder, "target"); assertTrue(target.mkdir());
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a = c.get();
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_select, 0);
            entry(a, "one.txt").performClick(); entry(a, "two.txt").performClick();
            assertTrue(entry(a, "one.txt").isSelected());
            assertEquals(a.getString(R.string.browser_selected_count, 2), toolbar(a).getTitle());
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_copy_selection, 0);
            browse(a, target);
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_paste, 0); flush(a);
            assertEquals("one", new String(Files.readAllBytes(new File(target, "one.txt").toPath()), "UTF-8"));
            assertTrue(new File(folder, "one.txt").exists()); assertTrue(new File(target, "two.txt").exists());
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_select, 0);
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_select_all, 0);
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_cut_selection, 0);
            File moved = new File(folder, "moved"); assertTrue(moved.mkdir()); browse(a, moved);
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_paste, 0); flush(a);
            assertEquals(0, target.list().length); assertEquals(2, moved.list().length);
            assertFalse(toolbar(a).getMenu().findItem(R.id.browser_paste).isVisible());
        }
    }

    @Test public void selectionSurvivesLayoutAndRecreationAndBackExitsItFirst() throws Exception {
        write("one.txt", "one"); write("two.txt", "two");
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a = c.get();
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_select, 0);
            entry(a, "one.txt").performClick();
            toolbar(a).getMenu().performIdentifierAction(R.id.local_games_action_view_mode, 0);
            assertTrue(entry(a, "one.txt").isSelected());
            c.recreate(); a = c.get(); flush(a);
            assertTrue(entry(a, "one.txt").isSelected()); assertFalse(entry(a, "two.txt").isSelected());
            a.onBackPressed();
            assertFalse(entry(a, "one.txt").isSelected());
            assertEquals(folder.getCanonicalFile(), field(a, "localCurrent"));
            assertTrue(toolbar(a).getMenu().findItem(R.id.browser_select).isVisible());
        }
    }

    @Test public void selectAllOnlyIncludesVisibleItemsAndDeleteStillRequiresConfirmation() throws Exception {
        File one = write("one.txt", "one"), two = write("two.txt", "two"), hidden = write(".secret", "secret");
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a = c.get();
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_select, 0);
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_select_all, 0);
            assertEquals(a.getString(R.string.browser_selected_count, 2), toolbar(a).getTitle());
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_delete_selection, 0);
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick(); flush(a);
            assertTrue(one.exists()); assertTrue(two.exists());
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_delete_selection, 0);
            dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick(); flush(a);
            assertFalse(one.exists()); assertFalse(two.exists()); assertTrue(hidden.exists());
        }
    }

    @Test public void aConflictInSecondItemDoesNotCopyTheFirstItem() throws Exception {
        write("one.txt", "one"); write("two.txt", "two");
        File target = write("target/two.txt", "precious").getParentFile();
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a = c.get();
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_select, 0);
            entry(a, "one.txt").performClick(); entry(a, "two.txt").performClick();
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_copy_selection, 0);
            browse(a, target); toolbar(a).getMenu().performIdentifierAction(R.id.browser_paste, 0); flush(a);
            assertFalse(new File(target, "one.txt").exists());
            assertEquals("precious", new String(Files.readAllBytes(new File(target, "two.txt").toPath()), "UTF-8"));
            AlertDialog error = (AlertDialog) ShadowDialog.getLatestDialog(); assertTrue(error.isShowing());
            assertNotNull(findMessage(error.getWindow().getDecorView()));
        }
    }

    @Test public void queuedBatchSurvivesRecreationWithoutRepeatingTheWrite() throws Exception {
        write("one.txt", "one");
        File target = new File(folder, "target"); assertTrue(target.mkdir());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a = c.get();
            entry(a, "one.txt").performLongClick(); popup(a).getMenu().performIdentifierAction(102, 0);
            browse(a, target);
            ExecutorService worker = (ExecutorService) field(a, "ioExecutor");
            worker.execute(() -> {
                entered.countDown();
                try { release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_paste, 0);
            Object model = field(a, "batchModel");
            assertEquals(Boolean.TRUE, field(a, "operating"));
            BrowserBatchViewModel other = new BrowserBatchViewModel(RuntimeEnvironment.getApplication());
            try {
                assertNull(other.takeInterrupted());
                assertTrue(RuntimeEnvironment.getApplication().getSharedPreferences("file_browser_batch", 0).contains("pending"));
                com.termux.ui.files.BrowserFileOperations files = (com.termux.ui.files.BrowserFileOperations) field(a, "fileOperations");
                assertThrows(java.io.IOException.class, () -> other.start(files, java.util.Collections.singletonList(new File(folder, "one.txt")),
                    target, com.termux.ui.files.BrowserBatchOperations.Action.COPY));
            } finally { other.onCleared(); }
            c.recreate(); a = c.get();
            assertSame(model, field(a, "batchModel")); assertSame(worker, field(a, "ioExecutor"));
            assertFalse(toolbar(a).getMenu().findItem(R.id.browser_paste).isEnabled());
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_paste, 0);
            release.countDown(); flush(a);
            assertEquals(1, target.list().length);
            assertEquals("one", new String(Files.readAllBytes(new File(target, "one.txt").toPath()), "UTF-8"));
            assertEquals(Boolean.FALSE, field(a, "operating"));
            assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("file_browser_batch", 0).contains("pending"));
            assertNull(ShadowDialog.getLatestDialog());
        } finally { release.countDown(); }
    }

    @Test public void refreshDropsDeletedSelectionAndInterruptedRequestIsNeverReplayed() throws Exception {
        File source = write("one.txt", "one");
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a = c.get();
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_select, 0);
            entry(a, "one.txt").performClick();
            assertTrue(source.delete()); browse(a, folder);
            assertEquals(a.getString(R.string.browser_selected_count, 0), toolbar(a).getTitle());
            assertFalse(toolbar(a).getMenu().findItem(R.id.browser_delete_selection).isEnabled());
        }
        File keep = write("keep.txt", "keep");
        RuntimeEnvironment.getApplication().getSharedPreferences("file_browser_batch", 0).edit()
            .putString("pending", "DELETE\n" + keep.getPath()).commit();
        try (ActivityController<GameFileManagerActivity> c = open()) {
            flush(c.get());
            assertTrue(keep.exists());
            assertNotNull(ShadowDialog.getLatestDialog());
            assertTrue(ShadowDialog.getLatestDialog().isShowing());
            assertFalse(RuntimeEnvironment.getApplication().getSharedPreferences("file_browser_batch", 0).contains("pending"));
        }
    }

    private View findMessage(View parent) {
        if (parent instanceof TextView && ((TextView) parent).getText().toString().contains("destination_exists")) return parent;
        if (parent instanceof ViewGroup) for (int i=0; i<((ViewGroup)parent).getChildCount(); i++) {
            View result = findMessage(((ViewGroup)parent).getChildAt(i)); if (result != null) return result;
        }
        return null;
    }

    @Test public void itemsKeepCompactMenusWithoutImportForOrdinaryFilesAndBlankAreasHaveNoMenu() throws Exception {
        write("file.txt", "one"); File child = new File(folder, "child"); assertTrue(child.mkdir());
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a=c.get();
            assertTrue(entry(a,"file.txt").performLongClick());
            flush(a);
            assertEquals(101, popup(a).getMenu().getItem(0).getItemId());
            assertEquals(5, popup(a).getMenu().size());
            assertNull(popup(a).getMenu().findItem(106));
            popup(a).dismiss();
            assertTrue(entry(a,"child").performLongClick());
            assertEquals(101,popup(a).getMenu().getItem(0).getItemId());
            assertNull(popup(a).getMenu().findItem(106)); popup(a).dismiss();
            for (int id : new int[]{R.id.local_game_file_manager_items,R.id.local_game_file_manager_scroll}) {
                assertFalse(a.findViewById(id).performLongClick());
                assertNull(popup(a));
            }
        }
    }
    @Test public void homeAndAllThreeRootsNeverOfferObjectOperations() throws Exception {
        try (ActivityController<GameFileManagerActivity> c=Robolectric.buildActivity(GameFileManagerActivity.class).setup()) {
            GameFileManagerActivity a=c.get(); GridLayout grid=a.findViewById(R.id.local_game_file_manager_items);
            assertEquals(3,grid.getChildCount());
            for(int i=0;i<3;i++)assertFalse(grid.getChildAt(i).performLongClick());
            assertFalse(a.findViewById(R.id.local_game_file_manager_scroll).performLongClick());
            GameStoragePaths paths=new GameStoragePaths(a.getFilesDir());
            for(File root:new File[]{a.getFilesDir(),paths.getImportedGamesDirectory(),android.os.Environment.getExternalStorageDirectory()}) {
                root.mkdirs(); browse(a,root);
                assertFalse(a.findViewById(R.id.local_game_file_manager_items).performLongClick());
                assertNull(popup(a));
            }
        }
    }
    @Test public void copyPasteAndCutPasteOperateOnlyWhenPasted() throws Exception {
        File source=write("one.txt","payload"); File target=new File(folder,"target");assertTrue(target.mkdir());
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();entry(a,"one.txt").performLongClick();popup(a).getMenu().performIdentifierAction(102,0);
            assertTrue(source.exists());browse(a,target);
            assertTrue(toolbar(a).getMenu().findItem(R.id.browser_paste).isEnabled());
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_paste,0);flush(a);
            assertEquals("payload",new String(Files.readAllBytes(new File(target,"one.txt").toPath()),"UTF-8"));assertTrue(source.exists());
            entry(a,"one.txt").performLongClick();popup(a).getMenu().performIdentifierAction(103,0);
            File source2=new File(target,"one.txt");assertTrue(source2.exists());File other=new File(folder,"other");assertTrue(other.mkdir());browse(a,other);
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_paste,0);flush(a);
            assertTrue(new File(other,"one.txt").isFile());assertFalse(source2.exists());assertTrue(((java.util.List<?>)field(a,"clipboardSources")).isEmpty());
        }
    }
    @Test public void clipboardSurvivesRecreationAndCanBeCancelled() throws Exception {
        File source=write("one.txt","payload");
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();entry(a,"one.txt").performLongClick();popup(a).getMenu().performIdentifierAction(103,0);
            c.recreate();a=c.get();assertEquals(java.util.Collections.singletonList(source),field(a,"clipboardSources"));assertEquals(true,field(a,"clipboardCut"));
            assertTrue(source.exists());toolbar(a).getMenu().performIdentifierAction(R.id.browser_cancel_clipboard,0);
            assertTrue(((java.util.List<?>)field(a,"clipboardSources")).isEmpty());assertFalse(toolbar(a).getMenu().findItem(R.id.browser_paste).isVisible());assertTrue(source.exists());
        }
    }
    @Test public void deleteCancelPreservesContentsAndConfirmDeletesSelectedDirectory() throws Exception {
        File child=write("child/file.txt","one").getParentFile();
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();entry(a,"child").performLongClick();popup(a).getMenu().performIdentifierAction(104,0);
            AlertDialog dialog=(AlertDialog)ShadowDialog.getLatestDialog();dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();flush(a);
            assertTrue(new File(child,"file.txt").isFile());
            entry(a,"child").performLongClick();popup(a).getMenu().performIdentifierAction(104,0);
            dialog=(AlertDialog)ShadowDialog.getLatestDialog();dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();flush(a);
            assertFalse(child.exists());assertEquals(folder.getCanonicalFile(),field(a,"localCurrent"));
        }
    }
    @Test public void hiddenFileAndFolderTogglePersistsInGrid() throws Exception { hiddenToggle(); }
    @Test @Config(qualifiers="w400dp-h800dp-port")
    public void hiddenFileAndFolderTogglePersistsInListPortrait() throws Exception {
        FileManagerViewMode.LIST.save(RuntimeEnvironment.getApplication());hiddenToggle();
    }
    private void hiddenToggle() throws Exception {
        write("plain.txt","one");write(".secret","two");assertTrue(new File(folder,".directory").mkdir());
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();View grid=a.findViewById(R.id.local_game_file_manager_items);
            assertNull(text(grid,".secret"));assertNull(text(grid,".directory"));assertNotNull(text(grid,"plain.txt"));
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_show_hidden,0);
            assertNotNull(text(grid,".secret"));assertNotNull(text(grid,".directory"));assertTrue(toolbar(a).getMenu().findItem(R.id.browser_show_hidden).isChecked());
            c.recreate();a=c.get();grid=a.findViewById(R.id.local_game_file_manager_items);assertNotNull(text(grid,".secret"));
            toolbar(a).getMenu().performIdentifierAction(R.id.browser_show_hidden,0);assertNull(text(grid,".secret"));assertNull(text(grid,".directory"));
        }
    }
    @Test public void openMenuAlwaysUsesChooserEvenWithDefaultAppAndPreservesReadGrant() throws Exception {
        File file=write("readme.txt","one");
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();
            Intent view=com.termux.ui.files.BrowserDocumentsProvider.openIntent(a,file);
            ResolveInfo app=new ResolveInfo();app.isDefault=true;app.activityInfo=new ActivityInfo();
            app.activityInfo.packageName="test.reader";app.activityInfo.name="test.reader.Main";app.activityInfo.exported=true;
            Shadows.shadowOf(a.getPackageManager()).addResolveInfoForIntent(view,app);
            assertNotNull(a.getPackageManager().resolveActivity(view,android.content.pm.PackageManager.MATCH_DEFAULT_ONLY));
            entry(a,"readme.txt").performLongClick();popup(a).getMenu().performIdentifierAction(101,0);
            Intent chooser=Shadows.shadowOf(a).getNextStartedActivity();assertEquals(Intent.ACTION_CHOOSER,chooser.getAction());
            Intent started=chooser.getParcelableExtra(Intent.EXTRA_INTENT);assertEquals(Intent.ACTION_VIEW,started.getAction());
            assertEquals("text/plain",started.getType());assertEquals("content",started.getData().getScheme());
            assertEquals(file.getCanonicalPath(),android.provider.DocumentsContract.getDocumentId(started.getData()));
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,started.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            assertEquals(started.getData(),started.getClipData().getItemAt(0).getUri());
            assertEquals(started.getClipData(),chooser.getClipData());
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,chooser.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
            // Selecting or dismissing the system chooser belongs to Android: no second launch here.
            assertNull(Shadows.shadowOf(a).getNextStartedActivity());
        }
    }

    @Test public void newToolbarActionIsAnIconAndCreatesFilesAndFolders() throws Exception { createFromToolbar(); }

    @Test @Config(qualifiers="w400dp-h800dp-port")
    public void newToolbarIconAndCreationAlsoWorkInPortrait() throws Exception { createFromToolbar(); }

    private void createFromToolbar() throws Exception {
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();
            android.view.MenuItem item=toolbar(a).getMenu().findItem(R.id.browser_new);
            assertTrue(item.isVisible());assertTrue(item.isEnabled());assertNotNull(item.getIcon());
            androidx.appcompat.view.menu.ActionMenuItemView action=a.findViewById(R.id.browser_new);
            assertNotNull(action);assertFalse(action.hasText());
            assertEquals(a.getString(R.string.browser_new),action.getContentDescription());
            AlertDialog dialog=createDialog(a,false);submitName(dialog,"notes");flush(a);
            File file=new File(folder,"notes");assertTrue(file.isFile());assertEquals(0,file.length());entry(a,"notes");
            dialog=createDialog(a,true);submitName(dialog,"folder");flush(a);
            assertTrue(new File(folder,"folder").isDirectory());entry(a,"folder");
        }
    }

    @Test public void cancelledOrInvalidCreationDoesNotWriteAndValidRetryWorks() throws Exception {
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();AlertDialog dialog=createDialog(a,false);
            nameInput(dialog.getWindow().getDecorView()).setText("cancelled");
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();flush(a);
            assertEquals(0,folder.list().length);
            dialog=createDialog(a,true);submitName(dialog,"../escape");flush(a);
            assertTrue(dialog.isShowing());assertNotNull(nameInput(dialog.getWindow().getDecorView()).getError());
            assertEquals(0,folder.list().length);submitName(dialog,"valid");flush(a);
            assertTrue(new File(folder,"valid").isDirectory());
        }
    }

    @Test public void createConflictPreservesExistingContentsAndReportsFailure() throws Exception {
        File precious=write("existing","precious");
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();submitName(createDialog(a,false),"existing");flush(a);
            assertEquals("precious",new String(Files.readAllBytes(precious.toPath()),"UTF-8"));
            AlertDialog error=(AlertDialog)ShadowDialog.getLatestDialog();assertTrue(error.isShowing());
            assertNotNull(text(error.getWindow().getDecorView(),a.getString(R.string.browser_conflict)+"\ndestination_exists: "+precious.getCanonicalPath()));
        }
    }

    @Test public void createKeepsOriginalDestinationAndHiddenPreference() throws Exception {
        File other=new File(folder,"other");assertTrue(other.mkdir());
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();AlertDialog dialog=createDialog(a,false);
            browse(a,other);submitName(dialog,".hidden");flush(a);
            assertTrue(new File(folder,".hidden").isFile());assertFalse(new File(other,".hidden").exists());
            browse(a,folder);assertNull(text(a.findViewById(R.id.local_game_file_manager_items),".hidden"));
            assertFalse(toolbar(a).getMenu().findItem(R.id.browser_show_hidden).isChecked());
        }
    }

    @Test @Config(shadows = com.termux.ui.platform.StorageTestOs.class)
    public void homeHidesCreateAndAllThreeRootsAllowCreatingChildren() throws Exception {
        try(ActivityController<GameFileManagerActivity> c=Robolectric.buildActivity(GameFileManagerActivity.class).setup().visible()) {
            GameFileManagerActivity a=c.get();assertFalse(toolbar(a).getMenu().findItem(R.id.browser_new).isVisible());
            assertFalse(toolbar(a).getMenu().findItem(R.id.browser_new_file).isEnabled());
            for(File root:new File[]{a.getFilesDir(),new GameStoragePaths(a.getFilesDir()).getImportedGamesDirectory(),android.os.Environment.getExternalStorageDirectory()}) {
                root.mkdirs();
                boolean shared = root.equals(android.os.Environment.getExternalStorageDirectory());
                if (shared) Shadows.shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
                browse(a,root);
                if (shared) {
                    Intent approval = Shadows.shadowOf(a).getNextStartedActivity();
                    assertNotNull(approval);
                    try (ActivityController<com.termux.ui.platform.ExternalStoragePermissionActivity> broker =
                            Robolectric.buildActivity(com.termux.ui.platform.ExternalStoragePermissionActivity.class, approval).setup()) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        while (!broker.get().isFinishing() && System.nanoTime() < deadline) {
                            Shadows.shadowOf(Looper.getMainLooper()).idle();
                            Thread.sleep(10);
                        }
                        assertTrue(broker.get().isFinishing());
                    }
                }
                assertTrue(toolbar(a).getMenu().findItem(R.id.browser_new).isVisible());
                submitName(createDialog(a,false),"created-child");flush(a);
                assertTrue(new File(root,"created-child").isFile());
            }
        }
    }

    @Test public void queuedCreationDisablesToolbarUntilItCompletes() throws Exception {
        CountDownLatch release=new CountDownLatch(1),entered=new CountDownLatch(1);
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();
            ((ExecutorService)field(a,"ioExecutor")).submit(() -> {
                entered.countDown();
                try { release.await(10,TimeUnit.SECONDS); }catch(InterruptedException e){Thread.currentThread().interrupt();}
            });
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            try {
                submitName(createDialog(a,false),"queued");
                assertFalse(toolbar(a).getMenu().findItem(R.id.browser_new).isEnabled());
                assertFalse(toolbar(a).getMenu().findItem(R.id.browser_new_file).isEnabled());
                assertFalse(new File(folder,"queued").exists());
                assertEquals(View.VISIBLE,a.findViewById(R.id.local_game_file_manager_progress).getVisibility());
            }finally{release.countDown();}
            flush(a);assertTrue(new File(folder,"queued").isFile());
            assertTrue(toolbar(a).getMenu().findItem(R.id.browser_new).isEnabled());
        }finally{release.countDown();}
    }

    @Test public void directoryChooserPreservesOnlySelectedTreeReadAccess() throws Exception {
        File child=new File(folder,"child");assertTrue(child.mkdir());
        try(ActivityController<GameFileManagerActivity> c=open()) {
            GameFileManagerActivity a=c.get();entry(a,"child").performLongClick();popup(a).getMenu().performIdentifierAction(101,0);
            Intent chooser=Shadows.shadowOf(a).getNextStartedActivity();assertEquals(Intent.ACTION_CHOOSER,chooser.getAction());
            Intent target=chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            assertEquals(android.provider.DocumentsContract.Document.MIME_TYPE_DIR,target.getType());
            assertEquals(child.getCanonicalPath(),android.provider.DocumentsContract.getTreeDocumentId(chooser.getClipData().getItemAt(0).getUri()));
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,chooser.getFlags());
            assertEquals(target.getClipData(),chooser.getClipData());assertNull(Shadows.shadowOf(a).getNextStartedActivity());
        }
    }
    @Test public void rootOffersToolbarPasteWithoutBlankMenu() throws Exception {
        write("one.txt", "one");
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a = c.get(); entry(a, "one.txt").performLongClick();
            popup(a).getMenu().performIdentifierAction(102, 0); browse(a, a.getFilesDir());
            assertFalse(a.findViewById(R.id.local_game_file_manager_scroll).performLongClick());
            assertNull(popup(a));
            assertTrue(toolbar(a).getMenu().findItem(R.id.browser_paste).isVisible());
            assertTrue(toolbar(a).getMenu().findItem(R.id.browser_paste).isEnabled());
        }
    }
    @Test public void missingSystemOpenHandlerShowsAnExplicitMessage() throws Exception {
        File child = new File(folder, "child"); assertTrue(child.mkdir());
        try (ActivityController<GameFileManagerActivity> c = open()) {
            GameFileManagerActivity a = c.get();
            Shadows.shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true);
            try {
                entry(a, "child").performLongClick(); popup(a).getMenu().performIdentifierAction(101, 0);
                assertEquals(a.getString(R.string.browser_no_open_app), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
            } finally { Shadows.shadowOf(RuntimeEnvironment.getApplication()).checkActivities(false); }
        }
    }

}
