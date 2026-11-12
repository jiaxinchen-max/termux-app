package com.termux.ui.desktop.domain;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DesktopDistroTest {

    private static DesktopDistro nativeDistro() {
        return new DesktopDistro("desk-1", "Plasma", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 30, DesktopUsbStorageMode.OFF);
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
        new DesktopDistro("p", "Debian", DesktopRunMode.PROOT, "debian", DesktopDe.KWIN_PLASMA, DesktopGlMode.ZINK, DesktopGuestGlMode.ZINK, "1920x1080", 60, DesktopUsbStorageMode.OFF);
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("p", "Debian", DesktopRunMode.PROOT, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.ZINK, DesktopGuestGlMode.ZINK, "1920x1080", 60, DesktopUsbStorageMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("p", "Debian", DesktopRunMode.NATIVE, "debian", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1920x1080", 60, DesktopUsbStorageMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("p", "Debian", DesktopRunMode.PROOT, "bad;alias", DesktopDe.KWIN_PLASMA, DesktopGlMode.ZINK, DesktopGuestGlMode.ZINK, "1920x1080", 60, DesktopUsbStorageMode.OFF));
    }

    @Test
    public void rejectsInvalidIdResolutionAndRefreshRate() {
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a/b", "n", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 30, DesktopUsbStorageMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280", 30, DesktopUsbStorageMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "0x720", 30, DesktopUsbStorageMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "99999x720", 30, DesktopUsbStorageMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 0, DesktopUsbStorageMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", "n", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 121, DesktopUsbStorageMode.OFF));
        assertThrows(IllegalArgumentException.class, () -> new DesktopDistro("a", " ", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 30, DesktopUsbStorageMode.OFF));
    }

    @Test
    public void withCopiesChangeOnlyTheirField() {
        DesktopDistro changed = nativeDistro().withRenderers(DesktopGlMode.LLVMPIPE,DesktopGuestGlMode.NONE)
            .withOutput("800x600", 45).withName("Other");
        assertEquals(DesktopGlMode.LLVMPIPE, changed.getHostGlMode());
        assertEquals("800x600", changed.getResolution());
        assertEquals(45, changed.getRefreshRate());
        assertEquals("Other", changed.getName());
        assertEquals("desk-1", changed.getId());
        assertEquals(DesktopRunMode.NATIVE, changed.getRunMode());
    }
    @Test public void usbModeDefaultsOffAndSurvivesEverySettingsCopy() {
        assertEquals(DesktopUsbStorageMode.OFF,nativeDistro().getUsbStorage());
        DesktopDistro changed=nativeDistro().withUsbStorage(DesktopUsbStorageMode.READ_ONLY)
            .withName("USB").withOutput("1920x1080",60).withRenderers(DesktopGlMode.LLVMPIPE,DesktopGuestGlMode.NONE);
        assertEquals(DesktopUsbStorageMode.READ_ONLY,changed.getUsbStorage());
        assertThrows(IllegalArgumentException.class,()->changed.withUsbStorage(null));
        assertThrows(IllegalArgumentException.class,()->DesktopUsbStorageMode.fromStorageValue("read-write"));
    }
}
