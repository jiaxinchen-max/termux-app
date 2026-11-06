package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    @Test public void gnomeNativeRequiresPythonAndRealSessionPrograms() throws Exception {
        DesktopEnvironment env=new DesktopEnvironment(temporaryFolder.getRoot());
        DesktopDistro d=new DesktopDistro("gn","GNOME",DesktopRunMode.NATIVE,"",DesktopDe.GNOME,
            DesktopGlMode.SYSTEM,"1280x720",60);
        touch("usr/bin/python3");touch("usr/bin/gnome-shell");assertFalse(env.isInstalled(d));
        touch("usr/bin/gnome-session");assertFalse(env.isInstalled(d));
        touch("usr/libexec/termux-gnome/launch.py");assertTrue(env.isInstalled(d));
        assertTrue(env.gnomeWaylandSocket(d).getPath().endsWith("home/.termux/gnome-gn/runtime/wayland-gnome"));
    }
    @Test public void gnomeProotRequiresPythonXlorieAndMatchingInstalledMarker() throws Exception {
        DesktopEnvironment env=new DesktopEnvironment(temporaryFolder.getRoot());
        DesktopDistro d=new DesktopDistro("gp","GNOME",DesktopRunMode.PROOT,"debian-gnome-1",DesktopDe.GNOME,
            DesktopGlMode.LLVMPIPE,"1280x720",60);
        touch("usr/bin/python3");touch("usr/bin/Xlorie");touch("usr/bin/proot-distro");
        touch("usr/libexec/termux-gnome/launch.py");
        touch("usr/var/lib/proot-distro/containers/debian-gnome-1/.desktop-de-xfce4");
        assertFalse(env.isInstalled(d));
        touch("usr/var/lib/proot-distro/containers/debian-gnome-1/.desktop-de-gnome");
        assertTrue(env.isInstalled(d));
    }
    @Test public void gnomeReadyMarkerMustIdentifyTheCurrentLauncher() throws Exception {
        File pidFile=new File(temporaryFolder.getRoot(),"gnome.pid");
        assertFalse(DesktopEnvironment.gnomeReadyMarkerMatches(pidFile,123));
        File marker=new File(pidFile.getPath()+".gnome-ready");
        try(FileWriter out=new FileWriter(marker)){out.write("123\n");}
        assertTrue(DesktopEnvironment.gnomeReadyMarkerMatches(pidFile,123));
        assertFalse(DesktopEnvironment.gnomeReadyMarkerMatches(pidFile,124));
        assertFalse(DesktopEnvironment.gnomeReadyMarkerMatches(pidFile,-1));
        try(FileWriter out=new FileWriter(marker)){out.write("ready\n");}
        assertFalse(DesktopEnvironment.gnomeReadyMarkerMatches(pidFile,123));
    }
}
