package com.termux.ui.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

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
    public void encodesEverythingTheScriptsNeed() {
        DesktopDistro distro = new DesktopDistro("p-1", "Debian KDE", DesktopRunMode.PROOT,
            "debian", DesktopGlMode.ZINK, "1920x1080", 60);
        Map<String, String> spec = parse(DesktopLaunchSpec.encode(distro,
            "/files/state/p-1.pid", "/files/state/p-1.status", "/files/logs/p-1.log"));
        assertEquals("proot", spec.get("DESKTOP_MODE"));
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
}
