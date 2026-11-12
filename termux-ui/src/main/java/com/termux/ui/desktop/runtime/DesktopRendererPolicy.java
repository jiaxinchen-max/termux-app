package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Explicit renderer choices shared by creation, settings and the shell launch contract. */
public final class DesktopRendererPolicy {
    private DesktopRendererPolicy() { }

    public static List<DesktopGlMode> allowedModes(DesktopRunMode runMode, DesktopDe de) {
        if (runMode==null || de==null || !de.isCreatable()) return Collections.emptyList();
        if (de==DesktopDe.HYPRLAND) return runMode==DesktopRunMode.PROOT
            ? Collections.unmodifiableList(Arrays.asList(DesktopGlMode.SYSTEM, DesktopGlMode.ZINK))
            : Collections.singletonList(DesktopGlMode.SYSTEM);
        if (de==DesktopDe.GNOME && runMode==DesktopRunMode.NATIVE)
            return Collections.singletonList(DesktopGlMode.SYSTEM);
        if (runMode==DesktopRunMode.PROOT && (de==DesktopDe.KWIN_PLASMA || de==DesktopDe.GNOME))
            return Collections.unmodifiableList(Arrays.asList(DesktopGlMode.ZINK, DesktopGlMode.LLVMPIPE));
        return Collections.unmodifiableList(Arrays.asList(DesktopGlMode.SYSTEM, DesktopGlMode.ZINK, DesktopGlMode.LLVMPIPE));
    }

    public static boolean supports(DesktopRunMode runMode, DesktopDe de, DesktopGlMode renderer) {
        return renderer!=null && allowedModes(runMode,de).contains(renderer);
    }

    public static void requireSupported(DesktopDistro distro) {
        if (distro.getUsbStorage()==DesktopUsbStorageMode.READ_ONLY
                && distro.getDe()!=DesktopDe.GNOME && distro.getDe()!=DesktopDe.XFCE4)
            throw new IllegalArgumentException("desktop_usb_storage_requires_gio_sidebar");
        if (!supports(distro.getRunMode(),distro.getDe(),distro.getGlMode()))
            throw new IllegalArgumentException("desktop_renderer_explicit_required");
        if (distro.getDe()==DesktopDe.GNOME && distro.getRunMode()==DesktopRunMode.NATIVE
                && distro.getRefreshRate()!=60)
            throw new IllegalArgumentException("desktop_gnome_native_refresh_requires_60");
    }
}
