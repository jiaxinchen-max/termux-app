package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Explicit renderer choices shared by creation, settings and the shell launch contract. */
public final class DesktopRendererPolicy {
    private DesktopRendererPolicy() { }

    public static List<DesktopGlMode> hostModes(DesktopRunMode mode, DesktopDe de) {
        if (mode == null || de == null || !de.isCreatable()) return Collections.emptyList();
        if (mode == DesktopRunMode.NATIVE) {
            if (!de.supportsNative()) return Collections.emptyList();
            if (de.isGnomeSession() || de == DesktopDe.HYPRLAND)
                return Collections.singletonList(DesktopGlMode.SYSTEM);
            return Arrays.asList(DesktopGlMode.SYSTEM, DesktopGlMode.ZINK, DesktopGlMode.LLVMPIPE);
        }
        switch (de) {
            case UBUNTU_GNOME: return Collections.singletonList(DesktopGlMode.SYSTEM);
            case HYPRLAND: return Collections.singletonList(DesktopGlMode.SYSTEM);
            case GNOME: return Arrays.asList(DesktopGlMode.SYSTEM, DesktopGlMode.ZINK);
            case KWIN_PLASMA: return Arrays.asList(DesktopGlMode.ZINK, DesktopGlMode.LLVMPIPE);
            default: return Arrays.asList(DesktopGlMode.SYSTEM, DesktopGlMode.ZINK, DesktopGlMode.LLVMPIPE);
        }
    }

    public static List<DesktopGuestGlMode> guestModes(DesktopRunMode mode, DesktopDe de) {
        if (mode == null || de == null || !de.isCreatable()) return Collections.emptyList();
        if (mode == DesktopRunMode.NATIVE) return de.supportsNative()
            ? Collections.singletonList(DesktopGuestGlMode.NONE) : Collections.emptyList();
        if (de == DesktopDe.UBUNTU_GNOME) return Collections.singletonList(DesktopGuestGlMode.LLVMPIPE);
        if (de == DesktopDe.XFCE4) return Collections.singletonList(DesktopGuestGlMode.DEFAULT);
        if (de == DesktopDe.HYPRLAND) return Arrays.asList(DesktopGuestGlMode.DEFAULT, DesktopGuestGlMode.ZINK);
        if (de == DesktopDe.GNOME || de == DesktopDe.KWIN_PLASMA)
            return Arrays.asList(DesktopGuestGlMode.ZINK, DesktopGuestGlMode.LLVMPIPE);
        return Collections.emptyList();
    }

    public static boolean supports(DesktopRunMode mode, DesktopDe de, DesktopGlMode host, DesktopGuestGlMode guest) {
        if (!hostModes(mode, de).contains(host) || !guestModes(mode, de).contains(guest)) return false;
        if (mode == DesktopRunMode.NATIVE) return guest == DesktopGuestGlMode.NONE;
        switch (de) {
            case UBUNTU_GNOME: return host == DesktopGlMode.SYSTEM && guest == DesktopGuestGlMode.LLVMPIPE;
            case XFCE4: return guest == DesktopGuestGlMode.DEFAULT;
            case HYPRLAND: return host == DesktopGlMode.SYSTEM;
            case GNOME: return host == DesktopGlMode.SYSTEM && guest == DesktopGuestGlMode.LLVMPIPE
                || host == DesktopGlMode.ZINK && guest == DesktopGuestGlMode.ZINK;
            case KWIN_PLASMA: return host == DesktopGlMode.ZINK && guest == DesktopGuestGlMode.ZINK
                || host == DesktopGlMode.LLVMPIPE && guest == DesktopGuestGlMode.LLVMPIPE;
            default: return false;
        }
    }

    public static void requireSupported(DesktopDistro distro) {
        if (distro.getUsbStorage()==DesktopUsbStorageMode.READ_ONLY
                && distro.getDe()!=DesktopDe.GNOME && distro.getDe()!=DesktopDe.XFCE4)
            throw new IllegalArgumentException("desktop_usb_storage_requires_gio_sidebar");
        if (!supports(distro.getRunMode(),distro.getDe(),distro.getHostGlMode(),distro.getGuestGlMode()))
            throw new IllegalArgumentException("desktop_renderer_explicit_required");
        if (distro.getDe().isGnomeSession() && distro.getRunMode()==DesktopRunMode.NATIVE
                && distro.getRefreshRate()!=60)
            throw new IllegalArgumentException("desktop_gnome_native_refresh_requires_60");
    }
}
