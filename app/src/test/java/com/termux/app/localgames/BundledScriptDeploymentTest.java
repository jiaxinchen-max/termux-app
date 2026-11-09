package com.termux.app.localgames;
import android.content.Context;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.service.LaunchScriptInstaller;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class BundledScriptDeploymentTest {
    @Test public void displayDiagnosticIsDeployedWithBothXServerImplementations() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        GameStoragePaths paths = new GameStoragePaths(context.getFilesDir());
        new LaunchScriptInstaller(context, paths).installXServerScripts();
        File helper = new File(paths.getRuntimeDirectory(), "x_server_dimensions.py");
        assertTrue(helper.isFile());
        assertTrue(new String(Files.readAllBytes(helper.toPath()), StandardCharsets.UTF_8)
            .contains("def root_dimensions(path):"));
    }

    @Test public void missingAndChangedScriptsArePublishedFromApkNormally() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        LaunchScriptInstaller installer = new LaunchScriptInstaller(context, new GameStoragePaths(context.getFilesDir()));
        File script = installer.installTermuxGlibcRuntime();
        byte[] bundled = Files.readAllBytes(script.toPath());
        assertTrue(bundled.length > 0);
        Files.write(script.toPath(), "#!/bin/sh\necho local\n".getBytes(StandardCharsets.UTF_8));
        installer.installTermuxGlibcRuntime();
        assertArrayEquals(bundled, Files.readAllBytes(script.toPath()));
        assertTrue(script.delete());
        installer.installTermuxGlibcRuntime();
        assertArrayEquals(bundled, Files.readAllBytes(script.toPath()));
    }
}
