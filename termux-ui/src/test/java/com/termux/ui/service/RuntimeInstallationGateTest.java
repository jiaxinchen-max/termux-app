package com.termux.ui.service;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.termux.ui.components.ComponentStoragePaths;
import com.termux.ui.data.FileComponentTaskRepository;
import com.termux.ui.data.FileRuntimeSetupTaskRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.ComponentTask;
import com.termux.ui.domain.ComponentTaskState;
import com.termux.ui.domain.RuntimeSetupTask;
import com.termux.ui.domain.RuntimeSetupTaskState;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

/** Covers the cross-task-type admission checks, including requireResetSlot -- Reset previously
 *  never participated in this gate at all, so a reset could run concurrently with a component
 *  delivery or rootfs build against the same runtime. */
public class RuntimeInstallationGateTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void requireResetSlotThrowsWhenAComponentTaskIsActive() throws Exception {
        File files = temporary.newFolder("reset-vs-component");
        saveComponentTask(files, "other-task", ComponentTaskState.INSTALLING);

        assertThrows(Exception.class, () ->
            RuntimeInstallationGate.requireResetSlot(files, "self-task"));
    }

    @Test
    public void requireResetSlotThrowsWhenARuntimeSetupTaskIsActive() throws Exception {
        File files = temporary.newFolder("reset-vs-setup");
        saveSetupTask(files, "other-task", RuntimeSetupTaskState.BUILDING);

        assertThrows(Exception.class, () ->
            RuntimeInstallationGate.requireResetSlot(files, "self-task"));
    }

    @Test
    public void requireResetSlotIgnoresItsOwnTaskId() throws Exception {
        File files = temporary.newFolder("reset-self-exclusion");
        saveSetupTask(files, "self-task", RuntimeSetupTaskState.BUILDING);

        RuntimeInstallationGate.requireResetSlot(files, "self-task"); // must not throw
    }

    @Test
    public void requireResetSlotAllowsWhenEverythingIsTerminal() throws Exception {
        File files = temporary.newFolder("reset-all-clear");
        saveComponentTask(files, "done-task", ComponentTaskState.INSTALLED);
        saveSetupTask(files, "done-task-2", RuntimeSetupTaskState.SUCCEEDED);

        RuntimeInstallationGate.requireResetSlot(files, "self-task"); // must not throw
    }

    @Test
    public void requireComponentSlotThrowsWhenARuntimeSetupTaskIsActive() throws Exception {
        File files = temporary.newFolder("component-vs-setup");
        saveSetupTask(files, "other-task", RuntimeSetupTaskState.BUILDING);

        assertThrows(Exception.class, () ->
            RuntimeInstallationGate.requireComponentSlot(files, "self-task"));
    }

    @Test
    public void requireRootfsSlotThrowsWhenAComponentTaskIsActive() throws Exception {
        File files = temporary.newFolder("rootfs-vs-component");
        saveComponentTask(files, "other-task", ComponentTaskState.DOWNLOADING);

        assertThrows(Exception.class, () ->
            RuntimeInstallationGate.requireRootfsSlot(files, "self-task"));
    }

    private static void saveComponentTask(File files, String taskId, ComponentTaskState state)
        throws Exception {
        ComponentTask task = new ComponentTask(taskId, "termux-glibc", 1,
            "https://example.invalid/pkg", 1, "0".repeat(64), 1, "", "", state, "", "");
        new FileComponentTaskRepository(new ComponentStoragePaths(files).getTasksDirectory())
            .save(task);
        assertTrue(task.getState() == state);
    }

    private static void saveSetupTask(File files, String taskId,
                                          RuntimeSetupTaskState state) throws Exception {
        RuntimeSetupTask task = RuntimeSetupTask.queued(taskId, "debian-13-games-rootfs",
            1, "a".repeat(64), "rootfs_hangover_11.16", "container-a", "container-a",
            false, 1L)
            .transition(state, "", 2L);
        new FileRuntimeSetupTaskRepository(
            new GameStoragePaths(files).getRuntimeSetupTasksDirectory()).save(task);
    }
}
