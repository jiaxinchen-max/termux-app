package com.termux.app.localgames;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;

import com.termux.localgames.api.LaunchRequest;
import com.termux.localgames.api.RuntimeProvisionRequest;
import com.termux.localgames.domain.LaunchExecutionMode;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.TermuxConstants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

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
    public void provisionIntentAlwaysUsesBackgroundAppShell() {
        Context context = RuntimeEnvironment.getApplication();
        RuntimeProvisionRequest request = new RuntimeProvisionRequest("build-1",
            "/private/provision.sh", "/private/build.provisionspec", "/private/runtime");

        Intent intent = TermuxLocalGamesHostFactory.createProvisionIntent(
            context, "/bin/sh", request);

        assertEquals(ExecutionCommand.Runner.APP_SHELL.getName(), intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_RUNNER));
        assertTrue(intent.getBooleanExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_BACKGROUND, false));
        assertNull(intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SESSION_ACTION));
        assertEquals("games-runtime-build-1", intent.getStringExtra(
            TermuxConstants.TERMUX_APP.TERMUX_SERVICE.EXTRA_SHELL_NAME));
    }

    private static Intent intentFor(LaunchExecutionMode mode) {
        Context context = RuntimeEnvironment.getApplication();
        LaunchRequest request = new LaunchRequest("task-1", "/private/start.sh",
            "/private/task.launchspec", "/private/runtime", mode);
        return TermuxLocalGamesHostFactory.createLaunchIntent(context, "/bin/sh", request);
    }
}
