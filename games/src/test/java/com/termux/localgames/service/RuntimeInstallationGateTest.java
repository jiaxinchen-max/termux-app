package com.termux.localgames.service;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.data.FileRuntimeProvisionTaskRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;
import com.termux.localgames.domain.RuntimeProvisionTask;
import com.termux.localgames.domain.RuntimeProvisionTaskState;

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
    public void requireResetSlotThrowsWhenARuntimeProvisionTaskIsActive() throws Exception {
        File files = temporary.newFolder("reset-vs-provision");
        saveProvisionTask(files, "other-task", RuntimeProvisionTaskState.BUILDING);

        assertThrows(Exception.class, () ->
            RuntimeInstallationGate.requireResetSlot(files, "self-task"));
    }

    @Test
    public void requireResetSlotIgnoresItsOwnTaskId() throws Exception {
        File files = temporary.newFolder("reset-self-exclusion");
        saveProvisionTask(files, "self-task", RuntimeProvisionTaskState.BUILDING);

        RuntimeInstallationGate.requireResetSlot(files, "self-task"); // must not throw
    }

    @Test
    public void requireResetSlotAllowsWhenEverythingIsTerminal() throws Exception {
        File files = temporary.newFolder("reset-all-clear");
        saveComponentTask(files, "done-task", ComponentTaskState.INSTALLED);
        saveProvisionTask(files, "done-task-2", RuntimeProvisionTaskState.SUCCEEDED);

        RuntimeInstallationGate.requireResetSlot(files, "self-task"); // must not throw
    }

    @Test
    public void requireComponentSlotThrowsWhenARuntimeProvisionTaskIsActive() throws Exception {
        File files = temporary.newFolder("component-vs-provision");
        saveProvisionTask(files, "other-task", RuntimeProvisionTaskState.BUILDING);

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
        ComponentTask task = new ComponentTask(taskId, "termux-glibc-runtime", 1,
            "https://example.invalid/pkg", 1, "0".repeat(64), 1, "", "", state, "", "");
        new FileComponentTaskRepository(new ComponentStoragePaths(files).getTasksDirectory())
            .save(task);
        assertTrue(task.getState() == state);
    }

    private static void saveProvisionTask(File files, String taskId,
                                          RuntimeProvisionTaskState state) throws Exception {
        RuntimeProvisionTask task = RuntimeProvisionTask.queued(taskId, "debian-13-games-rootfs",
            1, "a".repeat(64), "hangover-11.9-debian13-source", "container-a", "container-a", 1L)
            .transition(state, "", 2L);
        new FileRuntimeProvisionTaskRepository(
            new GameStoragePaths(files).getRuntimeProvisionTasksDirectory()).save(task);
    }
}
