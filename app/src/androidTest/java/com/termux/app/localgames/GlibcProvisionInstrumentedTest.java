package com.termux.app.localgames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.termux.app.TermuxActivity;
import com.termux.localgames.api.ComponentTasks;
import com.termux.localgames.api.LocalGames;
import com.termux.localgames.api.PrefixProvisionTasks;
import com.termux.localgames.components.ComponentStoragePaths;
import com.termux.localgames.data.ComponentTaskRepository;
import com.termux.localgames.data.FileComponentTaskRepository;
import com.termux.localgames.data.FileGameRepository;
import com.termux.localgames.data.FilePrefixProvisionTaskRepository;
import com.termux.localgames.data.FileRuntimeProfileRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.Game;
import com.termux.localgames.domain.ComponentTask;
import com.termux.localgames.domain.ComponentTaskState;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.localgames.domain.PrefixProvisionTask;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProvisionTaskState;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.Collections;

/** Runs GLIBC prefix provisioning through the real app Host and Termux AppShell. */
@RunWith(AndroidJUnit4.class)
public class GlibcProvisionInstrumentedTest {

    private static final String GAME_ID = "provision-smoke";
    private static final long TIMEOUT_MS = 12 * 60 * 1000;
    private static final String[] REQUIRED_COMPONENTS = {
        "glibc-prefix", "scripts", "box64-binaries", "prefix-apps", "libudev",
        "en-ru-locale", "virgl-mesa", "dxvk", "wine-9.3-vanilla-wow64"
    };

    @Test
    public void provisionsGlibcPrefixThroughTheAppShell() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        assertEquals("test must target the real app package", "com.termux",
            context.getPackageName());
        // TermuxActivity intentionally remains resumed while TermuxService owns app-shell
        // commands; ActivityScenario.close() cannot drive this activity to DESTROYED.
        ActivityScenario.launch(TermuxActivity.class);
            awaitFile(new File(context.getFilesDir(), "usr/bin/sh"), 3 * 60 * 1000,
                "Termux bootstrap shell");
            installRuntimeComponents(context);

            GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
            new FileGameRepository(paths.getLibraryDirectory())
                .save(new Game(GAME_ID, "Provision Smoke", "content://missing/tree/provision",
                    "Game.exe", ".", Collections.emptyList(), "", 0));
            new FileRuntimeProfileRepository(paths.getProfilesDirectory())
                .save(new RuntimeProfile(GAME_ID, "wine-9.3-vanilla-wow64", "virgl", "dxvk",
                    "pulseaudio", "1280x720", "STABILITY", Collections.emptyMap(), "xinput",
                    LaunchExecutionMode.APP_SHELL, Collections.emptyMap()));

            String taskId = PrefixProvisionTasks.enqueue(context, GAME_ID);
            PrefixProvisionTask result = awaitTerminal(paths, taskId);
            File provisionLog = new File(paths.getPrefixProvisionLogsDirectory(),
                taskId + ".log");

            assertEquals("GLIBC prefix provision must succeed: " + result.getErrorCode() +
                    "\n" + readTail(provisionLog, 80),
                RuntimeProvisionTaskState.SUCCEEDED, result.getState());
            File prefix = new File(context.getFilesDir(), "games/prefixes/" + GAME_ID);
            assertTrue("bootstrap marker must be written",
                new File(prefix, ".termux-box-bootstrap-done").isFile());
            assertEquals("prefix must record the activated wine package",
                "wine-9.3-vanilla-wow64",
                readFirstLine(new File(prefix, ".termux-box-wine-package")));
            assertTrue("box64 must exist in the launcher runtime",
                new File(context.getFilesDir(), "usr/glibc/bin/box64").isFile());
            assertTrue("runtime provisioning must install the independent Termux:X11 bridge",
                new File(context.getFilesDir(), "usr/bin/termux-x11").canExecute());
            assertTrue("Termux:X11 embedded loader must be installed",
                new File(context.getFilesDir(), "usr/libexec/termux-x11/loader.apk").isFile());
    }

    private void installRuntimeComponents(Context context) throws Exception {
        ComponentStoragePaths paths = new ComponentStoragePaths(context.getFilesDir());
        ComponentTaskRepository repository = new FileComponentTaskRepository(
            paths.getTasksDirectory());
        for (String componentId : REQUIRED_COMPONENTS) {
            String taskId = ComponentTasks.enqueue(context, componentId);
            ComponentTask task = awaitComponent(repository, taskId);
            assertEquals(componentId + " failed: " + task.getErrorCode() + " " +
                    task.getErrorMessage(), ComponentTaskState.INSTALLED, task.getState());
            assertTrue(componentId + " must be visible to the launcher runtime",
                LocalGames.requireHost(context).isRuntimeComponentAvailable(componentId,
                    task.getVersion(), task.getSha256()));
        }
    }

    private ComponentTask awaitComponent(ComponentTaskRepository repository, String taskId)
        throws Exception {
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            ComponentTask task = repository.find(taskId).orElse(null);
            if (task != null && task.getState().isTerminal()) return task;
            Thread.sleep(1000);
        }
        fail("component task did not finish within " + (TIMEOUT_MS / 60000) + " minutes");
        throw new IllegalStateException();
    }

    private PrefixProvisionTask awaitTerminal(GameStoragePaths paths, String taskId)
        throws Exception {
        FilePrefixProvisionTaskRepository repository = new FilePrefixProvisionTaskRepository(
            paths.getPrefixProvisionTasksDirectory());
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            PrefixProvisionTask task = repository.find(taskId).orElse(null);
            if (task != null && task.getState().isTerminal()) return task;
            Thread.sleep(2000);
        }
        fail("prefix provision did not finish within " + (TIMEOUT_MS / 60000) + " minutes");
        throw new IllegalStateException();
    }

    private static String readFirstLine(File file) throws Exception {
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.FileReader(file))) {
            String value = reader.readLine();
            return value == null ? "" : value.trim();
        }
    }

    private static String readTail(File file, int maximumLines) throws Exception {
        if (!file.isFile()) return "provision log missing: " + file;
        java.util.ArrayDeque<String> lines = new java.util.ArrayDeque<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
            new java.io.FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() == maximumLines) lines.removeFirst();
                lines.addLast(line);
            }
        }
        StringBuilder result = new StringBuilder("provision log tail:");
        for (String line : lines) result.append('\n').append(line);
        return result.toString();
    }

    private static void awaitFile(File file, long timeoutMs, String label) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (file.isFile() && file.length() > 0) return;
            Thread.sleep(500);
        }
        fail(label + " was not created");
    }
}
