package com.termux.app.localgames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import com.termux.ui.api.LaunchRequest;
import com.termux.ui.api.RuntimeSetupRequest;
import com.termux.ui.domain.LaunchExecutionMode;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.io.File;

@RunWith(RobolectricTestRunner.class)
public class TermuxLocalGamesHostFactoryTest {

    @Test
    public void appShellIntentRunsInBackgroundWithoutSessionAction() {
        Intent intent = intentFor(LaunchExecutionMode.APP_SHELL);

        assertEquals(ExecutionCommand.Runner.APP_SHELL.getName(), intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER));
        assertTrue(intent.getBooleanExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, false));
        assertNull(intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION));
    }

    @Test
    public void terminalIntentCreatesNamedSessionWithoutOpeningActivity() {
        Intent intent = intentFor(LaunchExecutionMode.TERMINAL_SESSION);

        assertEquals(ExecutionCommand.Runner.TERMINAL_SESSION.getName(), intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER));
        assertFalse(intent.getBooleanExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, true));
        assertEquals(Integer.toString(TermuxConstants.TERMUX_APP.TERMUX_SERVICE
                .VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY),
            intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION));
        assertEquals("local-games-task-1", intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_NAME));
    }

    @Test
    public void setupIntentCreatesNamedTerminalSessionWithoutOpeningActivity() {
        Context context = RuntimeEnvironment.getApplication();
        RuntimeSetupRequest request = new RuntimeSetupRequest("build-1",
            "/private/setup.sh", "/private/build.setupspec", "/private/runtime");

        Intent intent = TermuxLocalGamesHostFactory.createSetupIntent(
            context, "/bin/sh", request);

        assertEquals(ExecutionCommand.Runner.TERMINAL_SESSION.getName(), intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER));
        assertFalse(intent.getBooleanExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, true));
        assertEquals(Integer.toString(TermuxConstants.TERMUX_APP.TERMUX_SERVICE
                .VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_DONT_OPEN_ACTIVITY),
            intent.getStringExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION));
        assertEquals("games-runtime-setup-build-1", intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_NAME));
    }

    private static Intent intentFor(LaunchExecutionMode mode) {
        Context context = RuntimeEnvironment.getApplication();
        LaunchRequest request = new LaunchRequest("task-1", "/private/start.sh",
            "/private/task.launchspec", "/private/runtime", mode);
        return TermuxLocalGamesHostFactory.createLaunchIntent(context, "/bin/sh", request);
    }

    @Test
    public void setupPreludeReplaysLogBeforeScriptAndPreservesArgumentsAndExitStatus() throws Exception {
        File directory = Files.createTempDirectory("setup console '").toFile();
        File log = new File(directory, "preparation $(false).log");
        File script = new File(directory, "setup ' script.sh");
        File spec = new File(directory, "spec with spaces");
        try {
            Files.write(log.toPath(), "组件准备完成\n".getBytes(StandardCharsets.UTF_8));
            Files.write(script.toPath(), "printf 'script: %s\\n' \"$1\"; exit 17\n".getBytes(StandardCharsets.UTF_8));
            RuntimeSetupRequest request = new RuntimeSetupRequest("build-prelude", script.getPath(),
                spec.getPath(), directory.getPath(), log.getPath());
            Intent intent = TermuxLocalGamesHostFactory.createSetupIntent(
                RuntimeEnvironment.getApplication(), "/bin/sh", request);
            String[] args = intent.getStringArrayExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS);
            String[] command = new String[args.length + 1];
            command[0] = "/bin/sh";
            System.arraycopy(args, 0, command, 1, args.length);
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(17, process.waitFor());
            assertEquals("组件准备完成\n\nscript: " + spec.getPath() + "\n", output);

            assertTrue(log.delete());
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor() != 0);
            assertFalse(output.contains("script:"));
        } finally {
            log.delete();
            script.delete();
            directory.delete();
        }
    }

    @Test
    public void setupWithoutPreparationLogExecutesScriptDirectly() {
        RuntimeSetupRequest request = new RuntimeSetupRequest("archive", "/private/archive.sh",
            "/private/archive.spec", "/private/runtime");
        Intent intent = TermuxLocalGamesHostFactory.createSetupIntent(
            RuntimeEnvironment.getApplication(), "/bin/sh", request);
        assertArrayEquals(new String[] {request.getScriptPath(), request.getSpecPath()},
            intent.getStringArrayExtra(TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_ARGUMENTS));
    }
}
