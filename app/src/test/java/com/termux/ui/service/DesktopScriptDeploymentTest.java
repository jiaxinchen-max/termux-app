package com.termux.ui.service;

import android.app.Application;
import android.content.Context;
import com.termux.ui.data.GameStoragePaths;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, application = Application.class)
public class DesktopScriptDeploymentTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void freshInstallDeploysContainerComponentInstallers() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        GameStoragePaths paths = new GameStoragePaths(temporary.newFolder("files"));
        LaunchScriptInstaller.DesktopScripts scripts =
            new LaunchScriptInstaller(context, paths).installDesktopScripts();
        assertTrue(scripts.install.isFile());
        for (String name : new String[] {
                "desktop_install_glycin.py", "desktop_install_gio.py", "desktop_install_mesa.py"}) {
            File deployed = new File(paths.getRuntimeDirectory(), name);
            assertTrue("Missing installer at the shell script's sibling path: " + name,
                deployed.isFile());
            try (InputStream input = context.getAssets().open("local-games/" + name)) {
                ByteArrayOutputStream expected = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) expected.write(buffer, 0, count);
                assertArrayEquals(name, expected.toByteArray(), Files.readAllBytes(deployed.toPath()));
            }
        }
    }
}
