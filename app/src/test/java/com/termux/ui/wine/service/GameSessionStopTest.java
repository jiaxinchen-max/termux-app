package com.termux.ui.wine.service;

import com.termux.ui.api.*;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.wine.api.LaunchTasks;
import com.termux.ui.wine.data.FileLaunchTaskRepository;
import com.termux.ui.wine.domain.*;
import java.io.File;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GameSessionStopTest {
    private Object oldFactory;
    private GameStoragePaths paths;
    private FileLaunchTaskRepository repository;
    private final List<String> killed = new ArrayList<>();
    private boolean failStop;
    @Before public void prepare() throws Exception {
        Field factory = LocalGames.class.getDeclaredField("hostFactory"); factory.setAccessible(true);
        oldFactory = factory.get(null);
        paths = new GameStoragePaths(RuntimeEnvironment.getApplication().getFilesDir());
        repository = new FileLaunchTaskRepository(paths.getLaunchTasksDirectory());
        LocalGames.install(context -> new LocalGamesHost() {
            public boolean isRuntimeAvailable() { return true; }
            public void stopLaunch(long pid, boolean force) { fail("must stop the session tree, not just its shell"); }
            public void forceStopRuntimeProcesses(List<String> scopes) throws LaunchHostException {
                try {
                    assertFalse(repository.find("launch-stop").get().getState().isTerminal());
                } catch (java.io.IOException error) { throw new AssertionError(error); }
                if (failStop) throw new LaunchHostException("runtime_force_stop_failed", true, null);
                killed.addAll(scopes);
            }
        });
    }
    @After public void restore() throws Exception {
        Field factory = LocalGames.class.getDeclaredField("hostFactory"); factory.setAccessible(true); factory.set(null, oldFactory);
    }
    private void runningTask() throws Exception {
        repository.save(new LaunchTask("launch-stop", "game", LaunchTaskState.RUNNING, LaunchStage.WAITING_FIRST_FRAME,
            100, System.currentTimeMillis(), 12345, null, "", false, ""));
    }
    private ServiceController<LocalGameOrchestratorService> service() throws Exception {
        ServiceController<LocalGameOrchestratorService> controller = Robolectric.buildService(LocalGameOrchestratorService.class).create();
        Field executor = LocalGameOrchestratorService.class.getDeclaredField("executor"); executor.setAccessible(true);
        ((ScheduledExecutorService) executor.get(controller.get())).shutdownNow();
        return controller;
    }
    private void cancel(LocalGameOrchestratorService service) throws Exception {
        Method method = LocalGameOrchestratorService.class.getDeclaredMethod("cancel", String.class, boolean.class);
        method.setAccessible(true); method.invoke(service, "launch-stop", true);
    }
    @Test public void forcedStopPublishesCancellationOnlyAfterScopedTreeCleanup() throws Exception {
        ServiceController<LocalGameOrchestratorService> controller = service();
        try {
            runningTask();
            File lock = new File(paths.getLaunchLocksDirectory(), "launch-stop"); assertTrue(lock.mkdirs());
            new File(lock, "pid").createNewFile(); new File(lock, "termux-box-game.conf").createNewFile();
            cancel(controller.get());
            assertFalse("forced exit must release the launch lock", lock.exists());
            assertEquals(Collections.singletonList(new File(paths.getLaunchSpecsDirectory(), "launch-stop.launchspec").getCanonicalPath()), killed);
            assertEquals(LaunchTaskState.CANCELLED, repository.find("launch-stop").get().getState());
            cancel(controller.get()); // Repeated requests are harmless.
            assertEquals(1, killed.size());
        } finally { controller.destroy(); }
    }
    @Test public void forcedStopAlsoCleansCommandsBeforeTheirPidIsReported() throws Exception {
        ServiceController<LocalGameOrchestratorService> controller = service();
        try {
            repository.save(LaunchTask.queued("launch-stop", "game", System.currentTimeMillis()));
            cancel(controller.get());
            assertEquals(1, killed.size());
            assertEquals(LaunchTaskState.CANCELLED, repository.find("launch-stop").get().getState());
            assertTrue(com.termux.ui.wine.runtime.RuntimeEditingStop.shellCancelled(RuntimeEnvironment.getApplication().getFilesDir(), "local-games-launch-stop"));
        } finally { controller.destroy(); }
    }

    @Test public void failedStopNeverPublishesSuccessfulCancellation() throws Exception {
        ServiceController<LocalGameOrchestratorService> controller = service();
        try {
            runningTask(); failStop = true;
            InvocationTargetException error = assertThrows(InvocationTargetException.class, () -> cancel(controller.get()));
            assertTrue(error.getCause() instanceof LaunchHostException);
            assertEquals(LaunchTaskState.RUNNING, repository.find("launch-stop").get().getState());
        } finally { controller.destroy(); }
    }
    @Test public void forcedRequestDoesNotRaceWithGracefulShellCancelFile() throws Exception {
        runningTask();
        PersistentLocalGameOrchestrator orchestrator = new PersistentLocalGameOrchestrator(RuntimeEnvironment.getApplication());
        File marker = new File(paths.getLaunchCancelDirectory(), "launch-stop.cancel");
        orchestrator.cancel("launch-stop", true);
        assertFalse(marker.exists());
        android.content.Intent command = Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService();
        assertEquals(LaunchTasks.ACTION_CANCEL, command.getAction());
        assertTrue(command.getBooleanExtra(LaunchTasks.EXTRA_FORCE, false));
        orchestrator.cancel("launch-stop", false);
        assertTrue(marker.isFile());
    }
}
