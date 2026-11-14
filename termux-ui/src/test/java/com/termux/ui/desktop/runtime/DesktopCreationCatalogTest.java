package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.DesktopGuestGlMode;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;
import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DesktopCreationCatalogTest {

    private static DesktopDistro desktop(String id, DesktopRunMode mode, String container,
                                         DesktopDe de) {
        return new DesktopDistro(id, id, mode, container, de, DesktopGlMode.SYSTEM, (mode == DesktopRunMode.NATIVE ? DesktopGuestGlMode.NONE : DesktopGuestGlMode.UNRESOLVED), "1280x720", 30, DesktopUsbStorageMode.OFF);
    }

    @Test
    public void nativeOptionsHideCreatedEnvironments() {
        List<DesktopDistro> existing = Collections.singletonList(
            desktop("n1", DesktopRunMode.NATIVE, "", DesktopDe.KWIN_PLASMA));

        assertEquals(Arrays.asList(DesktopDe.XFCE4, DesktopDe.GNOME, DesktopDe.HYPRLAND, DesktopDe.UBUNTU_GNOME),
            DesktopCreationCatalog.nativeOptions(existing));
        assertEquals(Arrays.asList(DesktopDe.XFCE4, DesktopDe.KWIN_PLASMA, DesktopDe.GNOME, DesktopDe.HYPRLAND, DesktopDe.UBUNTU_GNOME),
            DesktopCreationCatalog.nativeOptions(Collections.emptyList()));
    }

    @Test
    public void hyprlandNativeIsUniqueAndGuestInstancesRemainAvailable() {
        List<DesktopDistro> existing = Collections.singletonList(
            desktop("h1", DesktopRunMode.NATIVE, "", DesktopDe.HYPRLAND));
        assertFalse(DesktopCreationCatalog.nativeOptions(existing).contains(DesktopDe.HYPRLAND));
        assertTrue(DesktopCreationCatalog.containerOptions().contains(DesktopDe.HYPRLAND));
        assertEquals("debian-hyprland-1", DesktopCreationCatalog.nextInstanceAlias(existing,
            "debian", DesktopDe.HYPRLAND));
    }

    @Test
    public void containerOptionsNeverHideAnything() {
        List<DesktopDistro> existing = Arrays.asList(
            desktop("c1", DesktopRunMode.PROOT, "debian-xfce4-1", DesktopDe.XFCE4),
            desktop("c2", DesktopRunMode.PROOT, "debian-xfce4-2", DesktopDe.XFCE4));

        assertEquals(Arrays.asList(DesktopDe.XFCE4, DesktopDe.KWIN_PLASMA, DesktopDe.GNOME, DesktopDe.HYPRLAND, DesktopDe.UBUNTU_GNOME),
            DesktopCreationCatalog.containerOptions());
        assertEquals(2, DesktopCreationCatalog.count(existing, "debian", DesktopDe.XFCE4));
    }

    @Test
    public void instanceAliasesNumberPerContainerAndEnvironment() {
        List<DesktopDistro> existing = Collections.singletonList(
            desktop("c1", DesktopRunMode.PROOT, "debian-xfce4-1", DesktopDe.XFCE4));

        assertEquals("debian-xfce4-2", DesktopCreationCatalog.nextInstanceAlias(existing,
            "debian", DesktopDe.XFCE4));
        assertEquals("debian-gnome-1",DesktopCreationCatalog.nextInstanceAlias(existing,"debian",DesktopDe.GNOME));
        assertEquals("debian-kwin-plasma-1", DesktopCreationCatalog.nextInstanceAlias(existing,
            "debian", DesktopDe.KWIN_PLASMA));
    }

    @Test
    public void ubuntuSupportsNativeStyleAndSelectsRealContainerBase() {
        assertTrue(DesktopDe.HYPRLAND.isCreatable());
        assertTrue(DesktopDe.UBUNTU_GNOME.isCreatable());
        assertTrue(DesktopCreationCatalog.environments().contains(DesktopDe.UBUNTU_GNOME));
        assertEquals(DesktopCreationCatalog.containerOptions(), DesktopCreationCatalog.environments());
        assertEquals(Arrays.asList("debian", "ubuntu"), DesktopCreationCatalog.containers());
        assertTrue(DesktopCreationCatalog.nativeOptions(Collections.emptyList()).contains(DesktopDe.UBUNTU_GNOME));
        assertEquals("ubuntu:noble", DesktopCreationCatalog.containerImage(DesktopDe.UBUNTU_GNOME));
        assertEquals("debian:trixie", DesktopCreationCatalog.containerImage(DesktopDe.GNOME));
        assertEquals("ubuntu:resolute", DesktopCreationCatalog.containerImage(DesktopDe.KWIN_PLASMA));
        assertEquals("ubuntu-kwin-plasma-1", DesktopCreationCatalog.nextInstanceAlias(Collections.emptyList(),
            DesktopCreationCatalog.containerBase(DesktopDe.KWIN_PLASMA), DesktopDe.KWIN_PLASMA));
        assertEquals("ubuntu-ubuntu-gnome-1", DesktopCreationCatalog.nextInstanceAlias(Collections.emptyList(),
            DesktopCreationCatalog.containerBase(DesktopDe.UBUNTU_GNOME), DesktopDe.UBUNTU_GNOME));
        assertTrue(DesktopDe.XFCE4.isCreatable());
        assertTrue(DesktopDe.KWIN_PLASMA.isCreatable());
        assertTrue(DesktopDe.GNOME.isCreatable());
        assertEquals("gnome",DesktopDe.GNOME.getStorageValue());
        assertEquals(DesktopDe.GNOME,DesktopDe.fromStorageValue("gnome"));
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
