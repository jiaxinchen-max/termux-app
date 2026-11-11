package com.termux.app;

import android.app.Activity;
import android.content.Context;
import androidx.drawerlayout.widget.DrawerLayout;
import com.termux.R;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxX11ActivityIntegration;
import com.termux.app.terminal.utils.FileUtils;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, shadows = TermuxX11BridgeInstallSessionTest.AssetsShadow.class)
public class TermuxX11BridgeInstallSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private RecordingActivity activity;
    private RecordingClient client;

    @Before public void setup() {
        activity = Robolectric.buildActivity(RecordingActivity.class).get();
        activity.drawer = new RecordingDrawer(activity);
        client = new RecordingClient(activity);
        activity.mTermuxTerminalSessionActivityClient = client;
        AssetsShadow.copied.clear();
    }

    @Test public void manualRebuildUsesVisibleInteractiveSessionInsteadOfBackgroundTask() {
        new TermuxX11ActivityIntegration(activity).reInstallX11StartScript(activity);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(Arrays.asList("install", "termux-x11-nightly-1.04.10-0-all.deb"), AssetsShadow.copied);
        assertNotNull(client.command);
        assertEquals(activity.getString(R.string.x11_bridge_install_session), client.sessionName);
        assertEquals(1, activity.terminalShown);
        assertEquals(1, activity.drawer.closed);
        assertNull(Shadows.shadowOf(activity).getNextStartedService());
    }

    @Test public void successfulInstallStreamsOutputBeforeFinishingAndKeepsShellOpen() throws Exception {
        File script = script("install.sh", "printf 'step-one\\n'\nread -r response\nprintf 'step-two\\n'\n");
        activity.installX11BridgeInTerminal(script);
        Process process = startCapturedCommand();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
                if (line.equals("step-one")) break;
            }
            assertTrue(output.toString(), output.toString().contains("step-one"));
            assertTrue(process.isAlive());
            assertTrue(output.toString().contains(activity.getString(R.string.x11_bridge_install_started)));
            process.getOutputStream().write("continue\n".getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertTrue(output.toString().contains("step-two"));
            assertTrue(output.toString().contains(activity.getString(R.string.x11_bridge_install_success)));
            assertTrue(output.toString().contains("SHELL_STILL_OPEN"));
        } finally {
            process.destroyForcibly();
        }
    }

    @Test public void failedInstallKeepsErrorAndOriginalExitCodeVisible() throws Exception {
        File script = script("failure.sh", "printf 'apt-install-error\\n' >&2\nexit 23\n");
        activity.installX11BridgeInTerminal(script);
        String output = runCapturedCommand();
        assertTrue(output, output.contains("apt-install-error"));
        assertTrue(output, output.contains(activity.getString(R.string.x11_bridge_install_failure) + " 23"));
        assertFalse(output.contains(activity.getString(R.string.x11_bridge_install_success)));
        assertTrue(output.contains("SHELL_STILL_OPEN"));
    }

    @Test public void specialPathIsExecutedAsOneArgumentWithoutCommandExpansion() throws Exception {
        File script = script("setup ' $(touch INJECTED).sh", "printf 'selected-installer\\n'\n");
        activity.installX11BridgeInTerminal(script);
        String output = runCapturedCommand();
        assertTrue(output, output.contains("selected-installer"));
        assertFalse(new File(temporary.getRoot(), "INJECTED").exists());
    }

    @Test @Config(qualifiers = "zh-rCN")
    public void chineseSessionShowsLocalizedFailure() throws Exception {
        File script = script("failure-zh.sh", "exit 19\n");
        activity.installX11BridgeInTerminal(script);
        String output = runCapturedCommand();
        assertEquals("X11 依赖安装", client.sessionName);
        assertTrue(output, output.contains("正在安装 X11 依赖…"));
        assertTrue(output, output.contains("安装失败，退出码： 19"));
    }

    private File script(String name, String content) throws Exception {
        File script = new File(temporary.getRoot(), name);
        Files.write(script.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return script;
    }

    private Process startCapturedCommand() throws Exception {
        return new ProcessBuilder("/bin/sh", "-c", client.command + "; printf '\\nSHELL_STILL_OPEN\\n'")
            .directory(temporary.getRoot()).redirectErrorStream(true).start();
    }

    private String runCapturedCommand() throws Exception {
        Process process = startCapturedCommand();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(process.waitFor(5, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            return output;
        } finally {
            process.destroyForcibly();
        }
    }

    public static class RecordingActivity extends TermuxActivity {
        RecordingDrawer drawer;
        int terminalShown;
        @Override public DrawerLayout getDrawer() { return drawer; }
        @Override public void showTerminalSurface() { terminalShown++; }
    }

    static class RecordingDrawer extends DrawerLayout {
        int closed;
        RecordingDrawer(Context context) { super(context); }
        @Override public void closeDrawers() { closed++; }
    }

    /** Capture only the existing terminal-session creation boundary, not result handling. */
    static class RecordingClient extends TermuxTerminalSessionActivityClient {
        String command;
        String sessionName;
        RecordingClient(TermuxActivity activity) { super(activity); }
        @Override public void addNewSessionAndRunCommand(String command, String sessionName) {
            this.command = command;
            this.sessionName = sessionName;
        }
        @Override public void addNewAutoCloseSessionAndRunCommand(String command, String sessionName) {
            fail("Installer must retain its ordinary interactive session");
        }
    }

    @Implements(FileUtils.class)
    public static class AssetsShadow {
        static final List<String> copied = new ArrayList<>();
        @Implementation protected static void copyAssetsFile2Phone(Activity activity, String name, String directory) {
            copied.add(name);
        }
    }
}
