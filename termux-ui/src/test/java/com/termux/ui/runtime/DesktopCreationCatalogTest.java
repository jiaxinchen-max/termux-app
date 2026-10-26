package com.termux.ui.runtime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.ui.domain.DesktopDe;
import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class DesktopCreationCatalogTest {

    private static DesktopDistro desktop(String id, DesktopRunMode mode, String container,
                                         DesktopDe de) {
        return new DesktopDistro(id, id, mode, container, de, DesktopGlMode.SYSTEM, "1280x720", 30);
    }

    @Test
    public void nativeOptionsHideCreatedEnvironments() {
        List<DesktopDistro> existing = Collections.singletonList(
            desktop("n1", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA));

        assertEquals(Collections.singletonList(DesktopDe.XFCE4),
            DesktopCreationCatalog.nativeOptions(existing));
        assertEquals(Arrays.asList(DesktopDe.XFCE4, DesktopDe.KWIN_PLASMA),
            DesktopCreationCatalog.nativeOptions(Collections.emptyList()));
    }

    @Test
    public void containerOptionsNeverHideAnything() {
        List<DesktopDistro> existing = Arrays.asList(
            desktop("c1", DesktopRunMode.PROOT, "debian-xfce4-1", DesktopDe.XFCE4),
            desktop("c2", DesktopRunMode.PROOT, "debian-xfce4-2", DesktopDe.XFCE4));

        assertEquals(Arrays.asList(DesktopDe.XFCE4, DesktopDe.KWIN_PLASMA),
            DesktopCreationCatalog.containerOptions());
        assertEquals(2, DesktopCreationCatalog.count(existing, "debian", DesktopDe.XFCE4));
    }

    @Test
    public void instanceAliasesNumberPerContainerAndEnvironment() {
        List<DesktopDistro> existing = Collections.singletonList(
            desktop("c1", DesktopRunMode.PROOT, "debian-xfce4-1", DesktopDe.XFCE4));

        assertEquals("debian-xfce4-2", DesktopCreationCatalog.nextInstanceAlias(existing,
            "debian", DesktopDe.XFCE4));
        assertEquals("debian-kwin-plasma-1", DesktopCreationCatalog.nextInstanceAlias(existing,
            "debian", DesktopDe.KWIN_PLASMA));
    }

    @Test
    public void comingSoonEnvironmentsAreNotCreatable() {
        assertFalse(DesktopDe.HYPRLAND.isCreatable());
        assertFalse(DesktopDe.UBUNTU_GNOME.isCreatable());
        assertTrue(DesktopDe.XFCE4.isCreatable());
        assertTrue(DesktopDe.KWIN_PLASMA.isCreatable());
    }

    @Test
    public void instanceLabelNumbersSiblings() {
        DesktopDistro first = desktop("c1", DesktopRunMode.PROOT, "debian-xfce4-1", DesktopDe.XFCE4);
        DesktopDistro second = desktop("c2", DesktopRunMode.PROOT, "debian-xfce4-2", DesktopDe.XFCE4);
        List<DesktopDistro> existing = Arrays.asList(first, second);

        assertEquals("#1", DesktopCreationCatalog.instanceLabel(first, existing));
        assertEquals("#2", DesktopCreationCatalog.instanceLabel(second, existing));
        assertEquals("", DesktopCreationCatalog.instanceLabel(
            desktop("n1", DesktopRunMode.NATIVE, "", DesktopDe.XFCE4), existing));
    }
}
