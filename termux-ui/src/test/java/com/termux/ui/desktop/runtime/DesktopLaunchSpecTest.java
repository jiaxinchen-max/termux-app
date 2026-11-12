package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DesktopLaunchSpecTest {

    private static Map<String, String> parse(String spec) {
        Map<String, String> result = new HashMap<>();
        for (String line : spec.split("\n")) {
            int index = line.indexOf('=');
            result.put(line.substring(0, index), line.substring(index + 1));
        }
        return result;
    }

    @Test
    public void hyprlandHasItsOwnPinnedBackendContractForBothClientModes() {
        for (DesktopRunMode mode : DesktopRunMode.values()) {
            DesktopDistro distro = new DesktopDistro("h-1", "Hyprland", mode,
                mode == DesktopRunMode.PROOT ? "debian-hyprland-1" : "", DesktopDe.HYPRLAND,
                DesktopGlMode.SYSTEM, "1280x720", 60);
            Map<String,String> spec = parse(DesktopLaunchSpec.encode(distro,
                "/files/h.pid", "/files/h.status", "/files/h.log"));
            assertEquals("hyprland", spec.get("DESKTOP_DE"));
            assertEquals("android", spec.get("DESKTOP_HYPRLAND_BACKEND"));
            assertEquals("hyprland-android-0.57.0-70ec30e7-r7", spec.get("DESKTOP_HYPRLAND_RELEASE"));
            assertFalse(spec.containsKey("DESKTOP_GNOME_BACKEND"));
        }
    }

    @Test
    public void hyprlandZinkChangesOnlyGuestRendererContract() {
        DesktopDistro distro = new DesktopDistro("hy-zink", "Hyprland", DesktopRunMode.PROOT,
            "debian-hyprland-1", DesktopDe.HYPRLAND, DesktopGlMode.ZINK, "1280x720", 30);
        Map<String,String> spec = parse(DesktopLaunchSpec.encode(distro,
            "/files/h.pid", "/files/h.status", "/files/h.log"));
        assertEquals("zink", spec.get("DESKTOP_GL_MODE"));
        assertEquals("system", spec.get("DESKTOP_HYPRLAND_HOST_GL_MODE"));
        assertEquals("zink", spec.get("DESKTOP_HYPRLAND_CLIENT_GL_MODE"));
        spec = parse(DesktopLaunchSpec.encode(distro.withGlMode(DesktopGlMode.SYSTEM),
            "/files/h.pid", "/files/h.status", "/files/h.log"));
        assertEquals("system", spec.get("DESKTOP_HYPRLAND_HOST_GL_MODE"));
        assertEquals("default", spec.get("DESKTOP_HYPRLAND_CLIENT_GL_MODE"));
    }

    @Test
    public void encodesEverythingTheScriptsNeed() {
        DesktopDistro distro = new DesktopDistro("p-1", "Debian KDE", DesktopRunMode.PROOT,
            "debian", DesktopGlMode.ZINK, "1920x1080", 60);
        Map<String, String> spec = parse(DesktopLaunchSpec.encode(distro,
            "/files/state/p-1.pid", "/files/state/p-1.status", "/files/logs/p-1.log"));
        assertEquals("proot", spec.get("DESKTOP_MODE"));
        assertEquals("host-pulseaudio", spec.get("DESKTOP_AUDIO_MODE"));
        assertEquals("proot-distro", spec.get("DESKTOP_REMOVE_METHOD"));
        assertEquals("zink", spec.get("DESKTOP_GL_MODE"));
        assertEquals("debian", spec.get("DESKTOP_PROOT_DISTRO"));
        assertEquals("1920", spec.get("DESKTOP_WIDTH"));
        assertEquals("1080", spec.get("DESKTOP_HEIGHT"));
        assertEquals("60", spec.get("DESKTOP_REFRESH_RATE"));
        assertEquals("/files/state/p-1.pid", spec.get("DESKTOP_PID_FILE"));
        assertEquals(DesktopLaunchSpec.RENDER_SOCKET_PATH, spec.get("DESKTOP_RENDER_SOCKET"));
        assertEquals(DesktopLaunchSpec.RELEASE_TAG, spec.get("DESKTOP_RELEASE_TAG"));
    }

    @Test
    public void nativeHasEmptyProotDistroAndNeverIncludesTheDisplayName() {
        DesktopDistro distro = new DesktopDistro("n", "$(rm -rf /) name", DesktopRunMode.NATIVE, "",
            DesktopGlMode.SYSTEM, "1280x720", 30);
        String encoded = DesktopLaunchSpec.encode(distro, "/a.pid", "/a.status", "/a.log");
        assertEquals("", parse(encoded).get("DESKTOP_PROOT_DISTRO"));
        assertTrue(!encoded.contains("rm -rf"));
    }

    @Test
    public void rejectsUnsafePathsAndRelativePaths() {
        DesktopDistro distro = new DesktopDistro("n", "n", DesktopRunMode.NATIVE, "",
            DesktopGlMode.SYSTEM, "1280x720", 30);
        assertThrows(IllegalArgumentException.class, () ->
            DesktopLaunchSpec.encode(distro, "/a b.pid", "/a.status", "/a.log"));
        assertThrows(IllegalArgumentException.class, () ->
            DesktopLaunchSpec.encode(distro, "/a.pid;x", "/a.status", "/a.log"));
        assertThrows(IllegalArgumentException.class, () ->
            DesktopLaunchSpec.encode(distro, "a.pid", "/a.status", "/a.log"));
    }
    @Test public void gnomeSpecSelectsExactBackendRendererAndContainerRelease() {
        DesktopDistro nativeGnome=new DesktopDistro("g","GNOME",DesktopRunMode.NATIVE,"",
            DesktopDe.GNOME,DesktopGlMode.SYSTEM,"1280x720",60);
        Map<String,String> nativeSpec=parse(DesktopLaunchSpec.encode(nativeGnome,"/p","/s","/l"));
        assertEquals("gnome",nativeSpec.get("DESKTOP_DE"));
        assertEquals("android",nativeSpec.get("DESKTOP_GNOME_BACKEND"));
        assertEquals("direct",nativeSpec.get("DESKTOP_GNOME_SESSION_STARTUP"));
        assertEquals("system",nativeSpec.get("DESKTOP_GNOME_RENDERER"));
        assertEquals("60",nativeSpec.get("DESKTOP_REFRESH_RATE"));
        assertFalse(nativeSpec.containsKey("DESKTOP_GNOME_DISPLAY_GL_MODE"));
        assertFalse(nativeSpec.containsKey("DESKTOP_GNOME_DISPLAY_BUFFER_MODE"));
        DesktopDistro prootGnome=new DesktopDistro("gp","GNOME",DesktopRunMode.PROOT,"debian-gnome-1",
            DesktopDe.GNOME,DesktopGlMode.LLVMPIPE,"1920x1080",90);
        Map<String,String> prootSpec=parse(DesktopLaunchSpec.encode(prootGnome,"/p","/s","/l"));
        assertEquals("x11",prootSpec.get("DESKTOP_GNOME_BACKEND"));
        assertEquals("direct",prootSpec.get("DESKTOP_GNOME_SESSION_STARTUP"));
        assertEquals("llvmpipe",prootSpec.get("DESKTOP_GNOME_RENDERER"));
        assertEquals("llvmpipe",prootSpec.get("DESKTOP_GL_MODE"));
        assertEquals("system",prootSpec.get("DESKTOP_GNOME_DISPLAY_GL_MODE"));
        assertEquals("native",prootSpec.get("DESKTOP_GNOME_DISPLAY_BUFFER_MODE"));
        assertEquals("trixie",prootSpec.get("DESKTOP_CONTAINER_CODENAME"));
        assertEquals("90",prootSpec.get("DESKTOP_REFRESH_RATE"));
        assertThrows(IllegalArgumentException.class,()->DesktopLaunchSpec.encode(nativeGnome.withOutput("1280x720",30),"/p","/s","/l"));
        assertThrows(IllegalArgumentException.class,()->DesktopLaunchSpec.encode(nativeGnome.withGlMode(DesktopGlMode.ZINK),"/p","/s","/l"));
        assertThrows(IllegalArgumentException.class,()->DesktopLaunchSpec.encode(prootGnome.withGlMode(DesktopGlMode.SYSTEM),"/p","/s","/l"));
        assertThrows(IllegalArgumentException.class,()->DesktopLaunchSpec.encode(nativeGnome.withGlMode(DesktopGlMode.LLVMPIPE),"/p","/s","/l"));
        assertThrows(IllegalArgumentException.class,()->DesktopLaunchSpec.encode(prootGnome.withGlMode(DesktopGlMode.AUTO),"/p","/s","/l"));
    }
    @Test public void prootGnomeZinkRequiresHostZinkAndDmabufBuffers() {
        DesktopDistro desktop=new DesktopDistro("gz","GNOME",DesktopRunMode.PROOT,"debian-gnome-zink",
            DesktopDe.GNOME,DesktopGlMode.ZINK,"1920x1080",90);
        Map<String,String> spec=parse(DesktopLaunchSpec.encode(desktop,"/p","/s","/l"));
        assertEquals("proot",spec.get("DESKTOP_MODE"));
        assertEquals("x11",spec.get("DESKTOP_GNOME_BACKEND"));
        assertEquals("direct",spec.get("DESKTOP_GNOME_SESSION_STARTUP"));
        assertEquals("zink",spec.get("DESKTOP_GL_MODE"));
        assertEquals("zink",spec.get("DESKTOP_GNOME_RENDERER"));
        assertEquals("zink",spec.get("DESKTOP_GNOME_DISPLAY_GL_MODE"));
        assertEquals("dmabuf",spec.get("DESKTOP_GNOME_DISPLAY_BUFFER_MODE"));
        assertEquals("trixie",spec.get("DESKTOP_CONTAINER_CODENAME"));
        assertEquals("90",spec.get("DESKTOP_REFRESH_RATE"));
    }
    @Test public void independentGnomeDisplayModeNeverLeaksIntoOtherDesktops() {
        for(DesktopDe de:new DesktopDe[]{DesktopDe.XFCE4,DesktopDe.KWIN_PLASMA}) {
            for(DesktopRunMode runMode:DesktopRunMode.values()) {
                DesktopDistro desktop=new DesktopDistro("other","Desktop",runMode,
                    runMode==DesktopRunMode.PROOT?"debian-test-1":"",de,DesktopGlMode.LLVMPIPE,"1280x720",60);
                Map<String,String> spec=parse(DesktopLaunchSpec.encode(desktop,"/p","/s","/l"));
                assertEquals("llvmpipe",spec.get("DESKTOP_GL_MODE"));
                assertFalse(spec.containsKey("DESKTOP_GNOME_DISPLAY_GL_MODE"));
                assertFalse(spec.containsKey("DESKTOP_GNOME_DISPLAY_BUFFER_MODE"));
                assertFalse(spec.containsKey("DESKTOP_GNOME_RENDERER"));
                assertFalse(spec.containsKey("DESKTOP_GNOME_SESSION_STARTUP"));
            }
        }
    }
    @Test public void usbStoragePolicyIsAlwaysExplicit() {
        DesktopDistro d=new DesktopDistro("n","n",DesktopRunMode.NATIVE,"",DesktopDe.XFCE4,DesktopGlMode.SYSTEM,"1280x720",60);
        assertEquals("off",parse(DesktopLaunchSpec.encode(d,"/p","/s","/l")).get("DESKTOP_USB_STORAGE"));
        assertEquals("read-only",parse(DesktopLaunchSpec.encode(d.withUsbStorage(DesktopUsbStorageMode.READ_ONLY),"/p","/s","/l")).get("DESKTOP_USB_STORAGE"));
    }
    @Test public void unsupportedUsbDesktopNeverProducesLaunchSpec() {
        DesktopDistro plasma=new DesktopDistro("p","Plasma",DesktopRunMode.NATIVE,"",DesktopGlMode.SYSTEM,"1280x720",60)
            .withUsbStorage(DesktopUsbStorageMode.READ_ONLY);
        assertEquals("desktop_usb_storage_requires_gio_sidebar",assertThrows(IllegalArgumentException.class,
            ()->DesktopLaunchSpec.encode(plasma,"/p","/s","/l")).getMessage());
    }
}
