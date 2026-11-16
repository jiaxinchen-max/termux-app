package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class DesktopRendererPolicyTest {
    @Test public void usbReadOnlyRequiresSupportedGioDesktopSidebar() {
        for(DesktopDe de:DesktopDe.values()) {
            DesktopDistro desktop=new DesktopDistro("usb", "USB", DesktopRunMode.NATIVE, "", de, DesktopGlMode.SYSTEM, DesktopGuestGlMode.NONE, "1280x720", 60, DesktopUsbStorageMode.OFF).withUsbStorage(DesktopUsbStorageMode.READ_ONLY);
            if(de==DesktopDe.GNOME) assertThrows(IllegalArgumentException.class,
                ()->DesktopRendererPolicy.requireSupported(desktop));
            else if(de==DesktopDe.XFCE4) DesktopRendererPolicy.requireSupported(desktop);
            else assertEquals("desktop_usb_storage_requires_gio_sidebar",assertThrows(IllegalArgumentException.class,
                ()->DesktopRendererPolicy.requireSupported(desktop)).getMessage());
        }
    }

    @Test public void retiredGnomeIsRejectedInBothModesWhileUbuntuRemainsSupported() {
        for (DesktopRunMode mode : DesktopRunMode.values()) {
            DesktopGuestGlMode guest = mode == DesktopRunMode.NATIVE
                ? DesktopGuestGlMode.NONE : DesktopGuestGlMode.LLVMPIPE;
            DesktopDistro retired = new DesktopDistro("retired", "GNOME", mode, mode == DesktopRunMode.PROOT ? "ubuntu-gnome-1" : "",
                DesktopDe.GNOME, DesktopGlMode.SYSTEM, guest, "1280x720", 60, DesktopUsbStorageMode.OFF);
            assertTrue(assertThrows(IllegalArgumentException.class,
                () -> DesktopRendererPolicy.requireSupported(retired)).getMessage().contains("retired"));
            DesktopRendererPolicy.requireSupported(new DesktopDistro("ubuntu", "Ubuntu GNOME", mode,
                mode == DesktopRunMode.PROOT ? "ubuntu-ubuntu-gnome-1" : "", DesktopDe.UBUNTU_GNOME, DesktopGlMode.SYSTEM, guest,
                "1280x720", 60, DesktopUsbStorageMode.OFF));
        }
    }

    @Test public void splitPairsRejectCrossLayerAndUnresolvedSelections() {
        String accepted = "native:xfce4:system:none native:xfce4:zink:none native:xfce4:llvmpipe:none "
            + "native:kwin_plasma:system:none native:kwin_plasma:zink:none native:kwin_plasma:llvmpipe:none "
            + "native:ubuntu_gnome:system:none native:hyprland:system:none "
            + "proot:xfce4:system:default proot:xfce4:zink:default proot:xfce4:llvmpipe:default "
            + "proot:kwin_plasma:system:llvmpipe proot:kwin_plasma:system:zink proot:kwin_plasma:zink:llvmpipe proot:kwin_plasma:zink:zink proot:kwin_plasma:llvmpipe:llvmpipe "
            + "proot:ubuntu_gnome:system:llvmpipe "
            + "proot:hyprland:system:default proot:hyprland:system:zink";
        java.util.Set<String> pairs = new java.util.HashSet<>(Arrays.asList(accepted.split(" ")));
        for (DesktopRunMode mode : DesktopRunMode.values()) for (DesktopDe de : DesktopDe.values())
            for (DesktopGlMode host : DesktopGlMode.values()) for (DesktopGuestGlMode guest : DesktopGuestGlMode.values()) {
                String pair=mode.getStorageValue()+":"+de.getStorageValue()+":"+host.getStorageValue()+":"+guest.getStorageValue();
                assertEquals(pair,pairs.contains(pair),DesktopRendererPolicy.supports(mode,de,host,guest));
            }
    }

}
