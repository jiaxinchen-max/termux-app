package com.termux.app;

import com.termux.app.terminal.TermuxTerminalSessionServiceClient;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.util.ReflectionHelpers;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;
import static org.robolectric.util.ReflectionHelpers.ClassParameter.from;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = {TerminalX11ScriptSessionTest.ServiceShadow.class,
    TerminalX11ScriptSessionTest.TerminalShadow.class})
public class TerminalX11ScriptSessionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private TermuxService service;
    private ServiceShadow shadow;

    @Before public void setup() {
        service = Robolectric.buildService(TermuxService.class).get();
        shadow = Shadow.extract(service);
        shadow.sessions = new TermuxShellManager(service);
        ReflectionHelpers.setField(service, "mShellManager", shadow.sessions);
    }

    @Test public void scriptRunsAsOwnedTerminalSessionAndRepeatedStartReusesIt() {
        TerminalSession script = service.startTerminalX11Script(new File("/tmp/desktop.sh"));
        assertTrue(service.isTerminalX11Script(script));
        assertTrue(terminal(script).started);
        assertTrue(shadow.created.getExecutionCommand().autoCloseOnExit);
        assertEquals(ExecutionCommand.Runner.TERMINAL_SESSION.getName(), shadow.created.getExecutionCommand().runner);
        assertSame(script, service.startTerminalX11Script(new File("/tmp/other.sh")));
        assertEquals(1, service.getTermuxSessionsSize());
    }

    @Test public void stoppingDesktopKillsAndRecyclesOnlyItsScriptSession() {
        TerminalSession script = service.startTerminalX11Script(new File("/tmp/desktop.sh"));
        ExecutionCommand other = new ExecutionCommand(500, null, new String[]{"-l"}, null,
            "/tmp", ExecutionCommand.Runner.TERMINAL_SESSION.getName(), false);
        TerminalSession unrelated = service.createTermuxSession(other).getTerminalSession();
        unrelated.initializeEmulator(80, 24);
        service.stopTerminalX11Script();
        assertEquals(1, terminal(script).kills);
        assertEquals(0, terminal(unrelated).kills);
        assertTrue(unrelated.isRunning());
        assertFalse(service.isTerminalX11Script(script));
        assertEquals(1, service.getTermuxSessionsSize());
        service.stopTerminalX11Script();
        assertEquals(1, terminal(script).kills);
    }

    @Test public void detachedActivityStillRecyclesNaturalExitAndAllowsNextStart() {
        TerminalSession script = service.startTerminalX11Script(new File("/tmp/desktop.sh"));
        service.unsetTermuxTerminalSessionClient();
        terminal(script).running = false;
        new TermuxTerminalSessionServiceClient(service).onSessionFinished(script);
        assertFalse(service.isTerminalX11Script(script));
        assertEquals(0, service.getTermuxSessionsSize());
        assertNotSame(script, service.startTerminalX11Script(new File("/tmp/next.sh")));
    }

    @Test public void replacingActivityClientKeepsOriginalSessionForDesktopExit() {
        TerminalSession script = service.startTerminalX11Script(new File("/tmp/desktop.sh"));
        service.unsetTermuxTerminalSessionClient();
        int[] updates = {0};
        service.setTermuxTerminalSessionClient(new com.termux.app.terminal.TermuxTerminalSessionActivityClient(null) {
            @Override public void termuxSessionListNotifyUpdated() { updates[0]++; }
        });
        assertTrue(service.isTerminalX11Script(script));
        assertSame(script, service.startTerminalX11Script(new File("/tmp/desktop.sh")));
        service.stopTerminalX11Script();
        assertEquals(1, terminal(script).kills);
        assertEquals(0, service.getTermuxSessionsSize());
        assertEquals(1, updates[0]);
    }

    @Test public void scriptPathRemainsOneArgumentWithoutShellExpansion() throws Exception {
        File dir = temp.newFolder("desktop ' $(bad)");
        File script = new File(dir, "start ' script.sh");
        Files.write(script.toPath(), "#!/bin/sh\nprintf 'selected-script'\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(script.setExecutable(true));
        service.startTerminalX11Script(script);
        String[] args = shadow.created.getExecutionCommand().arguments;
        String[] command = new String[args.length + 1];
        command[0] = "/bin/sh";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor());
        assertEquals("selected-script", output);
    }

    private static TerminalShadow terminal(TerminalSession session) { return Shadow.extract(session); }

    /** Only process creation/signals are replaced; Service ownership and removal use production code. */
    @Implements(TermuxService.class)
    public static class ServiceShadow extends org.robolectric.shadows.ShadowService {
        @RealObject TermuxService service;
        TermuxShellManager sessions;
        TermuxSession created;
        @Implementation protected TermuxSession createTermuxSession(ExecutionCommand command) {
            command.setState(ExecutionCommand.ExecutionState.EXECUTING);
            TerminalSession terminal = new TerminalSession("/bin/sh", "/tmp", new String[0], new String[0], 100, null);
            terminal.mSessionName = command.shellName;
            created = ReflectionHelpers.callConstructor(TermuxSession.class,
                from(TerminalSession.class, terminal), from(ExecutionCommand.class, command),
                from(TermuxSession.TermuxSessionClient.class, service), from(boolean.class, false));
            sessions.mTermuxSessions.add(created);
            return created;
        }
    }

    @Implements(TerminalSession.class)
    public static class TerminalShadow {
        boolean running;
        boolean started;
        int kills;
        @Implementation protected void initializeEmulator(int columns, int rows) { started = true; running = true; }
        @Implementation protected boolean isRunning() { return running; }
        @Implementation protected void finishIfRunning() { if (running) { kills++; running = false; } }
        @Implementation protected int getExitStatus() { return 0; }
    }
}
