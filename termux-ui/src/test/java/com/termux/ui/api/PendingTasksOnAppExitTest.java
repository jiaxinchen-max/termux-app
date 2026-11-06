package com.termux.ui.api;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.FileCustomComponentInstallTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;
import com.termux.ui.domain.CustomComponentInstallTask;
import com.termux.ui.domain.CustomComponentInstallTaskState;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.wine.data.FileLaunchTaskRepository;
import com.termux.ui.wine.data.FilePrefixSetupTaskRepository;
import com.termux.ui.wine.data.FileResetTaskRepository;
import com.termux.ui.wine.data.FileRootfsBackupTaskRepository;
import com.termux.ui.wine.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.wine.domain.LaunchStage;
import com.termux.ui.wine.domain.LaunchTask;
import com.termux.ui.wine.domain.LaunchTaskState;
import com.termux.ui.wine.domain.PrefixSetupTask;
import com.termux.ui.wine.domain.ResetTarget;
import com.termux.ui.wine.domain.ResetTask;
import com.termux.ui.wine.domain.ResetTaskState;
import com.termux.ui.wine.domain.RootfsBackupTask;
import com.termux.ui.wine.domain.RootfsBackupTaskState;
import com.termux.ui.wine.domain.RuntimeSetupTask;
import com.termux.ui.wine.domain.RuntimeSetupTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

public class PendingTasksOnAppExitTest {
    private static final String SHA =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void interruptsEveryRecoverableTaskFamilyWithoutLosingInputs() throws Exception {
        File files = temporary.newFolder();
        GameStoragePaths paths = new GameStoragePaths(files);
        FileComponentTaskRepository components = new FileComponentTaskRepository(
            new ComponentStoragePaths(files).getTasksDirectory());
        components.save(component("download").transition(ComponentTaskState.DOWNLOADING,
            25, "etag", "date", "", ""));
        FileRuntimeSetupTaskRepository runtime =
            new FileRuntimeSetupTaskRepository(paths.getRuntimeSetupTasksDirectory());
        // A stored timestamp can exceed the current wall clock after a clock adjustment.
        long future = System.currentTimeMillis() + 60000;
        runtime.save(RuntimeSetupTask.queued("runtime", "rootfs", 1, SHA,
            "source", "container", "container", false, future));
        FilePrefixSetupTaskRepository prefixes =
            new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory());
        prefixes.save(PrefixSetupTask.queued("prefix", "game", "wine", 10));
        FileResetTaskRepository resets = new FileResetTaskRepository(paths.getResetTasksDirectory());
        resets.save(ResetTask.queued("reset", ResetTarget.GLIBC, "wine", 10));
        FileLaunchTaskRepository launches = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
        launches.save(LaunchTask.queued("launch", "game", 10).transition(
            LaunchTaskState.RUNNING, LaunchStage.WAITING_FIRST_FRAME, 100, 123, null, "", true, "log")
            .withFirstFrame(20));
        FileRootfsBackupTaskRepository backups =
            new FileRootfsBackupTaskRepository(paths.getBackupTasksDirectory());
        backups.save(RootfsBackupTask.queued("backup", RootfsBackupTask.Kind.RESTORE,
            "/storage/emulated/0/archive.tar", false, 10));
        FileCustomComponentInstallTaskRepository custom =
            new FileCustomComponentInstallTaskRepository(paths.getCustomComponentInstallTasksDirectory());
        custom.save(CustomComponentInstallTask.queued("custom", "custom-wine",
            CustomRuntimeComponent.Kind.WINE, null, "Custom Wine", SHA, 10));

        PendingTasksOnAppExit.cancel(files);

        ComponentTask download = components.find("download").get();
        assertEquals(ComponentTaskState.PAUSED, download.getState());
        assertEquals(25, download.getDownloadedBytes());
        assertEquals("etag", download.getEtag());
        assertEquals("date", download.getLastModified());
        assertFalse(download.getState().shouldRecoverAutomatically());
        assertEquals(RuntimeSetupTaskState.CANCELLED, runtime.find("runtime").get().getState());
        assertEquals(future, runtime.find("runtime").get().getUpdatedAt());
        assertEquals("app_exit", runtime.find("runtime").get().getErrorCode());
        assertEquals(RuntimeSetupTaskState.CANCELLED, prefixes.find("prefix").get().getState());
        assertEquals(ResetTaskState.CANCELLED, resets.find("reset").get().getState());
        LaunchTask launch = launches.find("launch").get();
        assertEquals(LaunchTaskState.CANCELLED, launch.getState());
        assertEquals(123, launch.getPid());
        assertEquals(100, launch.getProgress());
        assertEquals("log", launch.getLogRef());
        assertFalse(launch.isRecoverable());
        assertEquals(RootfsBackupTaskState.FAILED, backups.find("backup").get().getState());
        assertEquals("app_exit", backups.find("backup").get().getErrorCode());
        assertEquals("/storage/emulated/0/archive.tar", backups.find("backup").get().getArchivePath());
        assertFalse(backups.find("backup").get().includesPrefixTemplates());
        assertEquals(CustomComponentInstallTaskState.FAILED, custom.find("custom").get().getState());
        assertEquals("app_exit", custom.find("custom").get().getErrorCode());
        assertEquals(SHA, custom.find("custom").get().getSha256());
    }

    @Test public void leavesFinishedFailedAndAlreadyPausedRecordsUnchanged() throws Exception {
        File files = temporary.newFolder();
        FileComponentTaskRepository components = new FileComponentTaskRepository(
            new ComponentStoragePaths(files).getTasksDirectory());
        for (ComponentTaskState state : new ComponentTaskState[] {
                ComponentTaskState.INSTALLED, ComponentTaskState.CANCELLED,
                ComponentTaskState.PAUSED, ComponentTaskState.FAILED}) {
            components.save(component(state.name()).transition(state, 100, "etag", "date",
                "original", "original message"));
        }
        GameStoragePaths paths = new GameStoragePaths(files);
        FilePrefixSetupTaskRepository prefixes =
            new FilePrefixSetupTaskRepository(paths.getPrefixSetupTasksDirectory());
        prefixes.save(PrefixSetupTask.queued("prefix", "game", "wine", 10)
            .transition(RuntimeSetupTaskState.SUCCEEDED, "", 20));
        File prefixFile = new File(paths.getPrefixSetupTasksDirectory(), "prefix.properties");
        byte[] original = Files.readAllBytes(prefixFile.toPath());

        PendingTasksOnAppExit.cancel(files);
        PendingTasksOnAppExit.cancel(files);

        for (ComponentTask task : components.list()) {
            assertEquals(task.getTaskId(), task.getState().name());
            assertEquals("original", task.getErrorCode());
            assertEquals("original message", task.getErrorMessage());
        }
        assertArrayEquals(original, Files.readAllBytes(prefixFile.toPath()));
    }

    @Test public void reportsCorruptionAndStillInterruptsOtherTaskFamilies() throws Exception {
        File files = temporary.newFolder();
        GameStoragePaths paths = new GameStoragePaths(files);
        File directory = paths.getLaunchTasksDirectory();
        assertTrue(directory.mkdirs());
        Files.write(new File(directory, "broken.properties").toPath(),
            "invalid=true\n".getBytes(StandardCharsets.UTF_8));
        FileResetTaskRepository resets = new FileResetTaskRepository(paths.getResetTasksDirectory());
        resets.save(ResetTask.queued("reset", ResetTarget.GLIBC, "wine", 10));
        FileRootfsBackupTaskRepository backups =
            new FileRootfsBackupTaskRepository(paths.getBackupTasksDirectory());
        backups.save(RootfsBackupTask.queued("backup", RootfsBackupTask.Kind.BACKUP, 10));
        try {
            PendingTasksOnAppExit.cancel(files);
            fail("Corrupt task metadata must be reported");
        } catch (IOException expected) {
            assertEquals(ResetTaskState.CANCELLED, resets.find("reset").get().getState());
            assertEquals(RootfsBackupTaskState.FAILED, backups.find("backup").get().getState());
        }
    }

    @Test public void emptyInstallationDoesNotCreateTaskDirectories() throws Exception {
        File files = temporary.newFolder();
        PendingTasksOnAppExit.cancel(files);
        assertEquals(0, files.list().length);
    }

    private static ComponentTask component(String id) {
        return ComponentTask.queued(id, "wine", 1, "https://example.com/wine.tar", 100, SHA);
    }
}
