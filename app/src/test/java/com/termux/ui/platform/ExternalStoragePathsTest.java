package com.termux.ui.platform;

import android.content.Context;
import android.os.Environment;
import androidx.appcompat.app.AlertDialog;
import com.termux.ui.activity.FixedBackupChooser;
import com.termux.ui.activity.GameFileManagerActivity;
import com.termux.ui.wine.activity.RuntimeBackupFlow;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExternalStoragePathsTest {
    private Context context() { return RuntimeEnvironment.getApplication(); }
    @Test public void pathsAreShallowAndComponentDefaultUsesPublicDownloads() {
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        assertEquals(new File(downloads, "Termux/components"), ExternalStoragePaths.components());
        assertEquals(new File(downloads, "Termux/backups"), ExternalBackupLocation.directory());
        assertEquals(ExternalStoragePaths.components(), new com.termux.ui.components.ComponentStoragePaths(context().getFilesDir()).getDownloadsDirectory());
    }
    @Test public void automaticNamesDoNotOverwriteAnExistingBackup() throws Exception {
        File first = ExternalBackupLocation.newArchive(context(), "wine-base-backup-123.tar.zst");
        Files.write(first.toPath(), new byte[]{1});
        File next = ExternalBackupLocation.newArchive(context(), "wine-base-backup-123.tar.zst");
        assertNotEquals(first, next);
        assertEquals("wine-base-backup-123-1.tar.zst", next.getName());
        assertArrayEquals(new byte[]{1}, Files.readAllBytes(first.toPath()));
    }
    @Test public void listIncludesOnlyMatchingDirectFilesAndNoDirectoryNavigation() throws Exception {
        File root = ExternalStoragePaths.requireWritable(context(), ExternalBackupLocation.directory());
        File backup = new File(root, "wine-base-backup-1.tar.zst"); assertTrue(backup.createNewFile());
        assertTrue(new File(root, "folder.tar.zst").mkdir());
        assertTrue(new File(root, "other.wine-cdrive.zip").createNewFile());
        File outside = new File(context().getFilesDir(), "outside.tar.zst"); assertTrue(outside.createNewFile());
        Files.createSymbolicLink(new File(root, "link.tar.zst").toPath(), outside.toPath());
        List<File> files = ExternalBackupLocation.archives(context(), ".tar.zst");
        assertEquals(java.util.Collections.singletonList(backup), files);
    }
    @Test public void cannotWriteShowsPermissionInstructionsInsteadOfAnotherLocation() throws Exception {
        File blocked = new File(context().getFilesDir(), "blocked"); assertTrue(blocked.createNewFile());
        try {
            ExternalStoragePaths.requireWritable(context(), blocked);
            fail("Must refuse an unwritable destination");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("storage access"));
            assertTrue(expected.getMessage().contains(blocked.getAbsolutePath()));
        }
    }
    @Test public void arbitraryBackupLocationsAreRejected() throws Exception {
        try {
            ExternalBackupLocation.requireArchive(new File(context().getFilesDir(), "backup.tar.zst"));
            fail("Must stay in the fixed directory");
        } catch (IOException expected) { assertEquals("backup_path_invalid", expected.getMessage()); }
    }
    @Test public void restoreDialogSelectsFilesWithoutSaveFieldsOrFolders() throws Exception {
        File backup = ExternalBackupLocation.newArchive(context(), "wine-base-backup-1.tar.zst");
        assertTrue(backup.createNewFile());
        assertTrue(new File(backup.getParentFile(), "nested").mkdir());
        try (ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class).setup()) {
            File[] selected = {null};
            AlertDialog dialog = FixedBackupChooser.show(c.get(), ".tar.zst", file -> selected[0] = file);
            assertEquals(1, dialog.getListView().getCount());
            assertEquals(backup.getName(), dialog.getListView().getAdapter().getItem(0));
            dialog.getListView().performItemClick(null, 0, 0);
            assertEquals(backup, selected[0]);
            dialog.dismiss();
        }
    }
    @Test public void rootfsSaveUsesGeneratedPathWithoutOpeningAChooser() throws Exception {
        try (ActivityController<GameFileManagerActivity> c = Robolectric.buildActivity(GameFileManagerActivity.class).setup()) {
            RuntimeBackupFlow flow = RuntimeBackupFlow.instance(); flow.attach(c.get(), null);
            try {
                File[] selected = {null};
                flow.createArchive("wine-base-backup-test.tar.zst", file -> selected[0] = file);
                assertEquals(new File(ExternalBackupLocation.directory(), "wine-base-backup-test.tar.zst"), selected[0]);
            } finally { flow.detach(c.get()); }
        }
    }
}
