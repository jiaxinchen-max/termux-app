package com.termux.ui.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class DesktopEnvironmentTest {

    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    private static final DesktopDistro NATIVE = new DesktopDistro("n", "n", DesktopRunMode.NATIVE,
        "", DesktopGlMode.SYSTEM, "1280x720", 30);
    private static final DesktopDistro PROOT = new DesktopDistro("p", "p", DesktopRunMode.PROOT,
        "debian", DesktopGlMode.ZINK, "1280x720", 30);

    private File touch(String relative) throws IOException {
        File file = new File(temporaryFolder.getRoot(), relative);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        assertTrue(file.createNewFile());
        return file;
    }

    @Test
    public void nativeNeedsStartPlasma() throws Exception {
        DesktopEnvironment environment = new DesktopEnvironment(temporaryFolder.getRoot());
        assertFalse(environment.isInstalled(NATIVE));
        touch("usr/bin/start-plasma");
        assertTrue(environment.isInstalled(NATIVE));
    }

    @Test
    public void prootNeedsKwinProotDistroAndTheContainer() throws Exception {
        DesktopEnvironment environment = new DesktopEnvironment(temporaryFolder.getRoot());
        touch("usr/bin/start-plasma");
        assertFalse(environment.isInstalled(PROOT));
        touch("usr/bin/proot-distro");
        assertFalse(environment.isInstalled(PROOT));
        assertTrue(new File(temporaryFolder.getRoot(),
            "usr/var/lib/proot-distro/containers/debian").mkdirs());
        // A container without the desktop packages is half-provisioned: still not installed.
        assertFalse(environment.isInstalled(PROOT));
        touch("usr/var/lib/proot-distro/containers/debian/.desktop-de-kwin_plasma");
        assertTrue(environment.isInstalled(PROOT));
    }

    @Test
    public void prootAcceptsTheLegacyInstalledRootfsLayout() throws Exception {
        DesktopEnvironment environment = new DesktopEnvironment(temporaryFolder.getRoot());
        touch("usr/bin/start-plasma");
        touch("usr/bin/proot-distro");
        assertTrue(new File(temporaryFolder.getRoot(),
            "usr/var/lib/proot-distro/installed-rootfs/debian").mkdirs());
        touch("usr/var/lib/proot-distro/installed-rootfs/debian/.desktop-de-kwin_plasma");
        assertTrue(environment.isInstalled(PROOT));
    }

    @Test
    public void prootNeedsTheNamedContainerNotAnyContainer() throws Exception {
        DesktopEnvironment environment = new DesktopEnvironment(temporaryFolder.getRoot());
        touch("usr/bin/start-plasma");
        touch("usr/bin/proot-distro");
        assertTrue(new File(temporaryFolder.getRoot(),
            "usr/var/lib/proot-distro/containers/ubuntu").mkdirs());
        assertFalse(environment.isInstalled(PROOT));
    }

    @Test
    public void waylandSocketPathFollowsKwinsRuntimeLayout() {
        File socket = new DesktopEnvironment(temporaryFolder.getRoot()).waylandSocket(10123);
        assertTrue(socket.getPath().endsWith("home/.termux/kwin-runtime/runtime-10123/wayland-0"));
    }

    @Test
    public void tailReadsFromTheEndOfTheLog() throws Exception {
        File log = temporaryFolder.newFile("kwin.log");
        try (FileWriter writer = new FileWriter(log)) {
            for (int i = 0; i < 100; i++) writer.write("noise line " + i + "\n");
            writer.write("last line\n");
        }
        String tail = DesktopEnvironment.tail(log, 64);
        assertEquals(64, tail.length());
        assertTrue(tail.endsWith("last line\n"));
        assertEquals("", DesktopEnvironment.tail(new File("/does/not/exist"), 64));
        assertFalse(DesktopEnvironment.logShowsReady(new File("/does/not/exist")));
    }

    @Test
    public void markerNearTheStartStaysReadyEvenInAHugeLog() throws Exception {
        File log = temporaryFolder.newFile("huge.log");
        try (FileWriter writer = new FileWriter(log)) {
            writer.write("kwin.android.egl: " + DesktopLaunchSpec.READY_LOG_MARKER + "\n");
            for (int i = 0; i < 60000; i++) writer.write("noise line " + i + "\n");
        }
        assertTrue(DesktopEnvironment.logShowsReady(log));
    }

    @Test
    public void missingMarkerIsNotReady() throws Exception {
        File log = temporaryFolder.newFile("starting.log");
        try (FileWriter writer = new FileWriter(log)) { writer.write("starting kwin\n"); }
        assertFalse(DesktopEnvironment.logShowsReady(log));
    }
}
