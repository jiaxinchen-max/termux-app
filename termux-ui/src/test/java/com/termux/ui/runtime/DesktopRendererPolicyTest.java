package com.termux.ui.runtime;

import com.termux.ui.domain.*;
import org.junit.Test;
import java.util.Collections;
import static org.junit.Assert.*;

public class DesktopRendererPolicyTest {
    @Test public void gnomeHasOnlyOneExactRendererForEachBackend() {
        assertEquals(Collections.singletonList(DesktopGlMode.SYSTEM),
            DesktopRendererPolicy.allowedModes(DesktopRunMode.NATIVE,DesktopDe.GNOME));
        assertEquals(Collections.singletonList(DesktopGlMode.LLVMPIPE),
            DesktopRendererPolicy.allowedModes(DesktopRunMode.PROOT,DesktopDe.GNOME));
        for(DesktopGlMode renderer:DesktopGlMode.values()) {
            assertEquals(renderer==DesktopGlMode.SYSTEM,DesktopRendererPolicy.supports(DesktopRunMode.NATIVE,DesktopDe.GNOME,renderer));
            assertEquals(renderer==DesktopGlMode.LLVMPIPE,DesktopRendererPolicy.supports(DesktopRunMode.PROOT,DesktopDe.GNOME,renderer));
        }
        assertFalse(DesktopRendererPolicy.supports(DesktopRunMode.NATIVE,DesktopDe.GNOME,null));
    }
    @Test public void unsupportedAndAutoModesNeverEnterTheChoiceList() {
        assertTrue(DesktopRendererPolicy.allowedModes(DesktopRunMode.NATIVE,DesktopDe.UBUNTU_GNOME).isEmpty());
        assertTrue(DesktopRendererPolicy.allowedModes(DesktopRunMode.PROOT,DesktopDe.HYPRLAND).isEmpty());
        for(DesktopDe de:DesktopDe.values()) for(DesktopRunMode mode:DesktopRunMode.values())
            assertFalse(DesktopRendererPolicy.allowedModes(mode,de).contains(DesktopGlMode.AUTO));
        assertFalse(DesktopRendererPolicy.supports(DesktopRunMode.PROOT,DesktopDe.KWIN_PLASMA,DesktopGlMode.SYSTEM));
    }
    @Test public void usbReadOnlyRequiresSupportedGioDesktopSidebar() {
        for(DesktopDe de:DesktopDe.values()) {
            DesktopDistro desktop=new DesktopDistro("usb","USB",DesktopRunMode.NATIVE,"",de,
                DesktopGlMode.SYSTEM,"1280x720",60).withUsbStorage(DesktopUsbStorageMode.READ_ONLY);
            if(de==DesktopDe.GNOME || de==DesktopDe.XFCE4) DesktopRendererPolicy.requireSupported(desktop);
            else assertEquals("desktop_usb_storage_requires_gio_sidebar",assertThrows(IllegalArgumentException.class,
                ()->DesktopRendererPolicy.requireSupported(desktop)).getMessage());
        }
    }
}
