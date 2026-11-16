package com.termux.ui.desktop.api;

import com.termux.ui.desktop.domain.*;
import com.termux.ui.desktop.runtime.DesktopLaunchSpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DesktopRestoreInitializationTest {
    @Rule public TemporaryFolder work = new TemporaryFolder();
    private final DesktopDistro desktop = new DesktopDistro("restored", "Restored", DesktopRunMode.PROOT,
        "import-97958826-4a9d-48ef-ab0a-5d271e9fb15a", DesktopDe.KWIN_PLASMA,
        DesktopGlMode.SYSTEM, DesktopGuestGlMode.LLVMPIPE, "1280x720", 30, DesktopUsbStorageMode.OFF);

    private File run(int exit) throws Exception {
        File folder = work.newFolder("task with spaces " + exit);
        File python = new File(folder, "python's executable");
        String body = "#!/bin/sh\nprintf '%s\\n' \"$@\"\nexit " + exit + "\n";
        Files.write(python.toPath(), body.getBytes(StandardCharsets.UTF_8));
        assertTrue(python.setExecutable(true));
        File result = new File(folder, "result.properties");
        File log = new File(folder, "initialize.log");
        String wrapper = DesktopRestorePrerequisites.restoreWrapper(python, "/trusted app/desktop_initialize.py",
            log, result, "initialize-task", desktop, new File(folder, "unused.ready"));
        File script = new File(folder, "restore.sh");
        Files.write(script.toPath(), wrapper.getBytes(StandardCharsets.UTF_8));
        assertEquals(exit, new ProcessBuilder("/bin/sh", script.getPath()).inheritIO().start().waitFor());
        String commands = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
        assertTrue(commands.contains("restore\n--de\nkwin_plasma\n"));
        assertTrue(commands.contains("--alias\n" + desktop.getProotDistro() + "\n"));
        assertTrue(commands.contains("--rendering-output\n" + new File(folder, "rendering.env") + "\n"));
        return result;
    }

    @Test public void successRequiresLocalInitializerToExitZero() throws Exception {
        File result = run(0);
        DesktopRestorePrerequisites.validateResult(result, "initialize-task", "restore-initialize");
    }
    @Test public void failedLocalInitializerNeverBecomesSuccessfulRestore() throws Exception {
        File result = run(73);
        try {
            DesktopRestorePrerequisites.validateResult(result, "initialize-task", "restore-initialize");
            fail("failed validation must reject restore");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("failed:73")); }
    }
    @Test public void hostPrerequisiteResultCannotStandInForLocalRestore() throws Exception {
        File result = run(0);
        try {
            DesktopRestorePrerequisites.validateResult(result, "initialize-task", "host-prerequisites");
            fail("a different operation must be rejected");
        } catch (IOException expected) { assertEquals("desktop_prerequisites_result_invalid", expected.getMessage()); }
    }
    @Test public void gnomeRestoreUsesFinalDesktopIdentityAndPersistentReadyPath() throws Exception {
        compositorRestore(DesktopDe.UBUNTU_GNOME, 0);
    }

    @Test public void audioFailureCannotPublishSuccessfulGnomeRestoration() throws Exception {
        compositorRestore(DesktopDe.UBUNTU_GNOME, 72);
    }

    @Test public void hyprlandRestorePreservesComponentsWithNewIdentity() throws Exception {
        compositorRestore(DesktopDe.HYPRLAND, 0);
    }

    @Test public void audioFailureCannotPublishSuccessfulHyprlandRestoration() throws Exception {
        compositorRestore(DesktopDe.HYPRLAND, 72);
    }

    private void compositorRestore(DesktopDe de, int audioExit) throws Exception {
        File folder = work.newFolder(de.getStorageValue() + " task " + audioExit);
        File assets = new File(folder, "trusted assets");
        assertTrue(assets.mkdir());
        File python = new File(folder, "python");
        Files.write(python.toPath(), "#!/bin/sh\nprintf '%s\\n' \"$@\"\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(python.setExecutable(true));
        Files.write(new File(assets, "desktop_common.sh").toPath(),
            ("prepare_desktop_audio() { [ \"$1\" = host-pulseaudio ] || return 99; return " + audioExit + "; }\n").getBytes(StandardCharsets.UTF_8));
        String id = "new-" + de.getStorageValue() + "-id";
        String alias = id + "-alias";
        DesktopDistro restored = new DesktopDistro(id, "Restored desktop", DesktopRunMode.PROOT,
            alias, de, DesktopGlMode.SYSTEM,
            de == DesktopDe.HYPRLAND ? DesktopGuestGlMode.DEFAULT : DesktopGuestGlMode.LLVMPIPE,
            "1280x720", 60, DesktopUsbStorageMode.OFF,
            de == DesktopDe.HYPRLAND ? "0.57.0-r7" : "", de == DesktopDe.HYPRLAND ? "classic-r7" : "");
        File ready = new File(folder, "persistent state/" + id + ".pid."
            + (de == DesktopDe.HYPRLAND ? "hyprland" : "gnome") + "-ready");
        File result = new File(folder, "result");
        File log = new File(folder, "log");
        File script = new File(folder, "restore.sh");
        Files.write(script.toPath(), DesktopRestorePrerequisites.restoreWrapper(python,
            new File(assets, "desktop_initialize.py").getPath(), log, result, "restore-task", restored, ready).getBytes(StandardCharsets.UTF_8));
        assertEquals(audioExit, new ProcessBuilder("/bin/sh", script.getPath()).inheritIO().start().waitFor());
        if (audioExit == 0) {
            DesktopRestorePrerequisites.validateResult(result, "restore-task", "restore-initialize");
            String commands = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
            if (de == DesktopDe.HYPRLAND) {
                assertTrue(commands.contains("--runtime-id\n0.57.0-r7\n--theme-id\nclassic-r7\n"));
                assertTrue(commands.contains("restore\n--de\nhyprland\n"));
            }
            assertTrue(commands.contains("--desktop-id\n" + id + "\n"));
            assertTrue(commands.contains("--alias\n" + alias + "\n"));
            assertTrue(commands.contains("--ready-file\n" + ready + "\n"));
            assertTrue(commands.contains("--size\n1280x720\n--refresh-rate\n60\n"));
            assertTrue(commands.contains("--render-socket\n" + DesktopLaunchSpec.RENDER_SOCKET_PATH + "\n"));
        } else {
            try { DesktopRestorePrerequisites.validateResult(result, "restore-task", "restore-initialize"); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("failed:" + audioExit)); }
            assertFalse(new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8).contains("--desktop-id"));
        }
    }
}
