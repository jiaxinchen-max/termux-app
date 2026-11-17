package com.termux.app.localgames;

import android.content.Context;
import android.content.ContextWrapper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TermuxGamesX11BridgeMigrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void existingBridgeBecomesReadOnlyWithoutReinstallingOrChangingPayload() throws Exception {
        File files = temporary.newFolder("files");
        File launcher = payload(files, "usr/bin/termux-x11");
        File x11 = payload(files, "usr/libexec/termux-x11/loader.apk");
        File display = payload(files, "usr/libexec/termux-display/loader.apk");
        byte[] original = Files.readAllBytes(x11.toPath());

        // No shell or installer fixture exists: an unnecessary reinstall would fail.
        TermuxGamesX11BridgeInstaller.ensureInstalled(context(files));
        TermuxGamesX11BridgeInstaller.ensureInstalled(context(files));

        assertReadOnly(x11);
        assertReadOnly(display);
        assertArrayEquals(original, Files.readAllBytes(x11.toPath()));
        assertArrayEquals(original, Files.readAllBytes(display.toPath()));
        assertTrue(launcher.canExecute());
        assertTrue(Files.getPosixFilePermissions(launcher.toPath()).contains(PosixFilePermission.OWNER_WRITE));
    }

    @Test public void optionalDisplayLoaderIsNotRequiredToRepairAnExistingX11Bridge() throws Exception {
        File files = temporary.newFolder("files");
        payload(files, "usr/bin/termux-x11");
        File x11 = payload(files, "usr/libexec/termux-x11/loader.apk");

        TermuxGamesX11BridgeInstaller.ensureInstalled(context(files));

        assertReadOnly(x11);
        assertFalse(new File(files, "usr/libexec/termux-display/loader.apk").exists());
    }

    private Context context(File files) {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getFilesDir() { return files; }
        };
    }

    private File payload(File files, String relative) throws Exception {
        File target = new File(files, relative);
        assertTrue(target.getParentFile().isDirectory() || target.getParentFile().mkdirs());
        Files.write(target.toPath(), "unchanged-payload".getBytes(StandardCharsets.UTF_8));
        Files.setPosixFilePermissions(target.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"));
        return target;
    }

    private void assertReadOnly(File loader) throws Exception {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(loader.toPath());
        assertFalse(permissions.contains(PosixFilePermission.OWNER_WRITE));
        assertFalse(permissions.contains(PosixFilePermission.GROUP_WRITE));
        assertFalse(permissions.contains(PosixFilePermission.OTHERS_WRITE));
        assertTrue(loader.canRead());
    }
}
