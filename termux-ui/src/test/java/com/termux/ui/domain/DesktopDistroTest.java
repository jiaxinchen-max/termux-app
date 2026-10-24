package com.termux.ui.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class DesktopDistroTest {

    private static DesktopDistro nativeDistro() {
        return new DesktopDistro("desk-1", "Plasma", DesktopRunMode.NATIVE, "",
            DesktopGlMode.SYSTEM, "1280x720", 30);
    }

    @Test
    public void exposesGeometry() {
        DesktopDistro distro = nativeDistro();
        assertEquals(1280, distro.getWidth());
        assertEquals(720, distro.getHeight());
        assertEquals(30, distro.getRefreshRate());
    }

    @Test
    public void prootNeedsADistroAliasAndNativeRejectsOne() {
        new DesktopDistro("p", "Debian", DesktopRunMode.PROOT, "debian", DesktopGlMode.ZINK,
            "1920x1080", 60);
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("p", "Debian",
            DesktopRunMode.PROOT, "", DesktopGlMode.ZINK, "1920x1080", 60));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("p", "Debian",
            DesktopRunMode.NATIVE, "debian", DesktopGlMode.SYSTEM, "1920x1080", 60));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("p", "Debian",
            DesktopRunMode.PROOT, "bad;alias", DesktopGlMode.ZINK, "1920x1080", 60));
    }

    @Test
    public void rejectsInvalidIdResolutionAndRefreshRate() {
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a/b", "n",
            DesktopRunMode.NATIVE, "", DesktopGlMode.SYSTEM, "1280x720", 30));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n",
            DesktopRunMode.NATIVE, "", DesktopGlMode.SYSTEM, "1280", 30));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n",
            DesktopRunMode.NATIVE, "", DesktopGlMode.SYSTEM, "0x720", 30));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n",
            DesktopRunMode.NATIVE, "", DesktopGlMode.SYSTEM, "99999x720", 30));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n",
            DesktopRunMode.NATIVE, "", DesktopGlMode.SYSTEM, "1280x720", 0));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n",
            DesktopRunMode.NATIVE, "", DesktopGlMode.SYSTEM, "1280x720", 121));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", " ",
            DesktopRunMode.NATIVE, "", DesktopGlMode.SYSTEM, "1280x720", 30));
    }

    @Test
    public void withCopiesChangeOnlyTheirField() {
        DesktopDistro changed = nativeDistro().withGlMode(DesktopGlMode.LLVMPIPE)
            .withOutput("800x600", 45).withName("Other");
        assertEquals(DesktopGlMode.LLVMPIPE, changed.getGlMode());
        assertEquals("800x600", changed.getResolution());
        assertEquals(45, changed.getRefreshRate());
        assertEquals("Other", changed.getName());
        assertEquals("desk-1", changed.getId());
        assertEquals(DesktopRunMode.NATIVE, changed.getRunMode());
    }
}
