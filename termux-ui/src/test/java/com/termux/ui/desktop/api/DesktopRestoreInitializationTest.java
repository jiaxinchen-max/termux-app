package com.termux.ui.desktop.api;

import com.termux.ui.desktop.domain.*;
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
            log, result, "initialize-task", desktop);
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
}
