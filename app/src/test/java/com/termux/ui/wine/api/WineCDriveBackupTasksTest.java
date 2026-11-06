package com.termux.ui.wine.api;

import android.content.Context;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.data.*;
import com.termux.ui.wine.domain.*;
import com.termux.ui.wine.recovery.*;
import com.termux.ui.wine.service.PersistentLocalGameOrchestrator;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class WineCDriveBackupTasksTest {
    @Test public void nativeCannotCreateBackupEvenWhenCalledWithoutMenu() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        new FileGameRepository(paths.getLibraryDirectory()).save(new Game("native-test", "Native", "file:///source", "test.exe", ".", Collections.emptyList(), "", 0));
        new FileRuntimeProfileRepository(paths.getProfilesDirectory()).save(RuntimeProfilePresets.create("native-test", RuntimeProfilePreset.RECOMMENDED));
        IOException error = assertThrows(IOException.class, () -> new WineCDriveBackupTasks(context)
            .enqueue("native-test", WineCDriveBackupTasks.Operation.BACKUP, new File("/sdcard/test.wine-cdrive.zip")));
        assertEquals("cdrive_proot_only", error.getMessage());
    }
    @Test public void archiveReservationBlocksLaunchPreparationAndDeletion() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File journal = new File(context.getFilesDir(), "front/runtime/cdrive-backup.properties"); journal.getParentFile().mkdirs();
        Properties job = new Properties(); job.setProperty("status", "RUNNING");
        try (OutputStream output = new FileOutputStream(journal)) { job.store(output, null); }
        assertThrows(com.termux.ui.wine.runtime.OrchestrationException.class, () -> new PersistentLocalGameOrchestrator(context).launch("game"));
        assertEquals("cdrive_backup_busy", assertThrows(IOException.class, () -> RuntimeWarmup.warm(context,
            RuntimeProfilePresets.create("game", RuntimeProfilePreset.RECOMMENDED))).getMessage());
        assertEquals("cdrive_backup_busy", assertThrows(IOException.class, () -> new GameUninstaller(context.getFilesDir())
            .execute(GameUninstallPlan.keepPrivateAssets("game"))).getMessage());
    }
    @Test public void newArchiveWaitsForPreviousWorkerToReleaseOwnership() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        java.lang.reflect.Field executing = WineCDriveBackupTasks.class.getDeclaredField("executing");
        executing.setAccessible(true); executing.setBoolean(null, true);
        try {
            assertEquals("cdrive_backup_busy", assertThrows(IOException.class, () -> new WineCDriveBackupTasks(context)
                .enqueue("game", WineCDriveBackupTasks.Operation.BACKUP, new File("/sdcard/test.wine-cdrive.zip"))).getMessage());
        } finally { executing.setBoolean(null, false); }
    }
}
