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
    @org.junit.Test
    public void oldHyprlandReadinessNeverMarksNewSupervisorReady() throws Exception {
        java.io.File directory = java.nio.file.Files.createTempDirectory("hyprland-marker").toFile();
        java.io.File pid = new java.io.File(directory, "session.pid");
        java.io.File marker = new java.io.File(pid.getPath() + ".hyprland-ready");
        try {
            java.nio.file.Files.write(marker.toPath(), "123\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            org.junit.Assert.assertTrue(DesktopEnvironment.hyprlandReadyMarkerMatches(pid, 123));
            org.junit.Assert.assertFalse(DesktopEnvironment.hyprlandReadyMarkerMatches(pid, 124));
            org.junit.Assert.assertFalse(DesktopEnvironment.hyprlandReadyMarkerMatches(pid, 0));
        } finally {
            marker.delete(); directory.delete();
        }
    }


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
    public void hyprlandUpgradeRequiresTheFixedRuntimeAndMatchingManifest() throws Exception {
        DesktopEnvironment environment = new DesktopEnvironment(temporaryFolder.getRoot());
        DesktopDistro distro = new DesktopDistro("hy", "Hyprland", DesktopRunMode.NATIVE,
            "", DesktopDe.HYPRLAND, DesktopGlMode.SYSTEM, "1280x720", 30);
        for (String program : new String[]{"python3", "dbus-daemon", "xfce4-terminal", "thunar",
                "eww", "foot", "fish", "starship", "fc-cache", "xfce4-panel", "swaybg", "xfconf-query", "dbus-send"}) {
            touch("usr/bin/" + program);
        }
        touch("usr/lib/xfce4/notifyd/xfce4-notifyd");
        File genmon = touch("usr/lib/xfce4/panel/plugins/libgenmon.so");
        String[] runtimeFiles = {"bin/Hyprland", "bin/Xwayland",
            "lib/hyprland/android/libhyprland-system-gl.so", "lib/hyprland/android/libvndksupport.so"};
        for (String file : runtimeFiles) touch("usr/opt/termux-hyprland/0.57.0-r2/" + file);
        try (FileWriter writer = new FileWriter(touch("usr/opt/termux-hyprland/0.57.0-r2/.installed"))) {
            writer.write("4e936063e9841d5044cfcdb02563ff529e749c055d1703fa61a35cde8ad7fd4e\n");
        }
        assertFalse(environment.isInstalled(distro));
        for (String file : runtimeFiles) touch("usr/opt/termux-hyprland/0.57.0-r7/" + file);
        File marker = touch("usr/opt/termux-hyprland/0.57.0-r7/.installed");
        try (FileWriter writer = new FileWriter(marker)) {
            writer.write("4e936063e9841d5044cfcdb02563ff529e749c055d1703fa61a35cde8ad7fd4e\n");
        }
        assertFalse(environment.isInstalled(distro));
        try (FileWriter writer = new FileWriter(marker)) {
            writer.write("6024fd61a4923031ce9d7c4d35c1d545e75cbff16fe1ae9ab8aab86a0167f948\n");
        }
        assertTrue(environment.isInstalled(distro));
        touch("usr/bin/proot-distro");
        String container = "usr/var/lib/proot-distro/containers/debian-hyprland-1/";
        touch(container + ".desktop-de-hyprland");
        DesktopDistro guest = new DesktopDistro("hy-guest", "Hyprland", DesktopRunMode.PROOT,
            "debian-hyprland-1", DesktopDe.HYPRLAND, DesktopGlMode.SYSTEM, "1280x720", 30);
        assertTrue(environment.isInstalled(guest));
        assertFalse(environment.isInstalled(guest.withGlMode(DesktopGlMode.ZINK)));
        File zinkMarker = touch(container + ".desktop-de-hyprland-zink");
        touch(container + "rootfs/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json");
        touch(container + "rootfs/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so");
        touch(container + "rootfs/usr/bin/python3");
        assertFalse(environment.isInstalled(guest.withGlMode(DesktopGlMode.ZINK)));
        try (FileWriter writer = new FileWriter(zinkMarker)) {
            writer.write("b711d1d9ab5537ee9085da8896dfe2115e791b33aa317fe37538f276c967d687\n");
        }
        assertTrue(environment.isInstalled(guest.withGlMode(DesktopGlMode.ZINK)));
        assertTrue(genmon.delete());
        assertFalse(environment.isInstalled(distro));
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
        touch("usr/libexec/termux-gnome/launch.py");assertFalse(env.isInstalled(d));
        String[] services={"ca.desrt.dconf", "org.gtk.vfs.Daemon", "org.gnome.Nautilus"};
        for (String service:services) touch("usr/share/dbus-1/services/"+service+".service");
        assertTrue(env.isInstalled(d));
        for (String service:services) {
            File file=new File(temporaryFolder.getRoot(),"usr/share/dbus-1/services/"+service+".service");
            assertTrue(file.delete());
            assertFalse(env.isInstalled(d));
            touch("usr/share/dbus-1/services/"+service+".service");
            assertTrue(env.isInstalled(d));
        }
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
