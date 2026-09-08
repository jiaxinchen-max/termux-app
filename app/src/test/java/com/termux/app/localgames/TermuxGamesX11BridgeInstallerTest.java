package com.termux.app.localgames;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public class TermuxGamesX11BridgeInstallerTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installationRequiresExecutableLauncherAndLoaderPayload() throws Exception {
        File files = temporary.newFolder("files");
        File launcher = write(new File(files, "usr/bin/termux-x11"), "launcher");
        assertTrue(launcher.setExecutable(true, false));

        assertFalse(TermuxGamesX11BridgeInstaller.isInstalled(files));

        write(new File(files, "usr/libexec/termux-x11/loader.apk"), "loader");
        assertTrue(TermuxGamesX11BridgeInstaller.isInstalled(files));

        assertTrue(launcher.setExecutable(false, false));
        assertFalse(TermuxGamesX11BridgeInstaller.isInstalled(files));
    }

    private static File write(File file, String value) throws Exception {
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }
}
