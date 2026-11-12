package com.termux.ui.desktop.api;

import android.content.Context;
import com.termux.ui.api.*;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import com.termux.ui.desktop.domain.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class DesktopMutationTest {
    private Object oldFactory;
    private int kills;
    private boolean failKill;
    private GameStoragePaths paths;
    private FileDesktopDistroRepository repository;

    @Before public void setup() throws Exception {
        Context context = RuntimeEnvironment.getApplication(); paths = new GameStoragePaths(context.getFilesDir());
        repository = new FileDesktopDistroRepository(paths.getDesktopDistrosDirectory());
        Field factory = LocalGames.class.getDeclaredField("hostFactory"); factory.setAccessible(true); oldFactory = factory.get(null);
        LocalGames.install(ignored -> new LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public void forceStopRuntimeProcesses(List<String> scopes) throws LaunchHostException {
                kills++;
                assertFalse(scopes.isEmpty());
                if (failKill) throw new LaunchHostException("runtime_force_stop_failed", true, null);
            }
        });
    }
    @After public void restore() throws Exception {
        Field factory = LocalGames.class.getDeclaredField("hostFactory"); factory.setAccessible(true); factory.set(null, oldFactory);
    }

    @Test public void bothBackendsForceStopBeforeEditingEvenWhenStopping() throws Exception {
        DesktopTasks tasks = new DesktopTasks(RuntimeEnvironment.getApplication());
        for (DesktopRunMode backend : DesktopRunMode.values()) {
            DesktopDistro distro = desktop(backend); repository.save(distro);
            write(paths.getDesktopStatusFile(distro.getId()), "stopping\n");
            tasks.save(distro.withName("Edited").withOutput("1920x1080", 60));
            assertEquals("Edited", repository.find(distro.getId()).get().getName());
            assertEquals("1920x1080", repository.find(distro.getId()).get().getResolution());
            assertEquals("stopped\n", new String(Files.readAllBytes(paths.getDesktopStatusFile(distro.getId()).toPath()), StandardCharsets.US_ASCII));
        }
        assertEquals(DesktopRunMode.values().length, kills);
    }

    @Test public void deletionKillsBeforeRemovingRecordForEveryReportedState() throws Exception {
        DesktopTasks tasks = new DesktopTasks(RuntimeEnvironment.getApplication());
        for (DesktopRunMode backend : DesktopRunMode.values()) {
            for (String status : new String[] {"starting", "running", "stopping", "exit:1", "stopped"}) {
                DesktopDistro distro = desktop(backend); repository.save(distro);
                write(paths.getDesktopStatusFile(distro.getId()), status + "\n");
                int before = kills; tasks.delete(distro);
                assertEquals(before + 1, kills);
                assertFalse(repository.find(distro.getId()).isPresent());
            }
        }
    }

    @Test public void failedTerminationPreservesInventoryAndSettings() throws Exception {
        DesktopTasks tasks = new DesktopTasks(RuntimeEnvironment.getApplication());
        DesktopDistro distro = desktop(DesktopRunMode.NATIVE); repository.save(distro);
        failKill = true;
        assertThrows(IOException.class, () -> tasks.delete(distro));
        assertTrue(repository.find(distro.getId()).isPresent());
        assertThrows(IOException.class, () -> tasks.save(distro.withName("Must not persist")));
        assertEquals(distro.getName(), repository.find(distro.getId()).get().getName());
    }

    @Test public void queuedArchiveIsCancelledInsteadOfBlockingEdit() throws Exception {
        DesktopTasks tasks = new DesktopTasks(RuntimeEnvironment.getApplication());
        DesktopDistro distro = desktop(DesktopRunMode.PROOT); repository.save(distro);
        File journal = new File(paths.getDesktopDirectory(), "archive-task.properties");
        Properties job = new Properties(); job.setProperty("desktopId", distro.getId());
        job.setProperty("status", "RUNNING"); job.setProperty("taskId", "queued-export");
        try (OutputStream output = new FileOutputStream(journal)) { job.store(output, null); }
        synchronized (DesktopOperationGate.LOCK) { DesktopOperationGate.reserveArchive(); }
        try {
            tasks.save(distro.withName("Edited"));
            try (InputStream input = new FileInputStream(journal)) { job.load(input); }
            assertEquals("FAILED", job.getProperty("status"));
            assertEquals("Edited", repository.find(distro.getId()).get().getName());
            assertFalse(DesktopOperationGate.isArchiveActive());
        } finally { synchronized (DesktopOperationGate.LOCK) { DesktopOperationGate.releaseArchive(); } }
    }

    private DesktopDistro desktop(DesktopRunMode backend) {
        return new DesktopDistro("desktop-" + UUID.randomUUID(), "Desktop", backend, backend == DesktopRunMode.PROOT ? "test-container" : "", DesktopDe.XFCE4, backend == DesktopRunMode.PROOT ? DesktopGlMode.LLVMPIPE : DesktopGlMode.SYSTEM, (backend == DesktopRunMode.NATIVE ? DesktopGuestGlMode.NONE : DesktopGuestGlMode.DEFAULT), "1280x720", 60, DesktopUsbStorageMode.OFF);
    }
    private void write(File file, String text) throws IOException {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), text.getBytes(StandardCharsets.US_ASCII));
    }
}
