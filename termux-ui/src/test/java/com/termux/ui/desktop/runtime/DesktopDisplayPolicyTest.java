package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.*;
import com.termux.ui.desktop.data.FileDesktopDistroRepository;
import java.io.File;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DesktopDisplayPolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private DesktopDistro desktop(DesktopRunMode mode, DesktopDe de, String resolution) {
        return new DesktopDistro("output-" + mode + "-" + de, "Output", mode,
            mode == DesktopRunMode.PROOT ? "ubuntu-output-1" : "", de, DesktopGlMode.SYSTEM,
            mode == DesktopRunMode.NATIVE ? DesktopGuestGlMode.NONE
                : de == DesktopDe.KWIN_PLASMA || de.isGnomeSession() ? DesktopGuestGlMode.LLVMPIPE : DesktopGuestGlMode.DEFAULT,
            resolution, de.isGnomeSession() ? 60 : 120, DesktopUsbStorageMode.OFF);
    }

    @Test public void allSupportedDesktopsRoundTripNativeWithoutFreezingItsPixels() throws Exception {
        FileDesktopDistroRepository repository = new FileDesktopDistroRepository(new File(temporary.getRoot(), "desktops"));
        for (DesktopRunMode mode : DesktopRunMode.values()) for (DesktopDe de : DesktopDe.values()) {
            if (!de.isCreatable()) continue;
            DesktopDistro desktop = desktop(mode, de, "native");
            repository.save(desktop);
            DesktopDistro loaded = repository.find(desktop.getId()).get();
            assertEquals("native", loaded.getResolution());
            assertThrows(IllegalStateException.class, loaded::getWidth);
            assertEquals("2400x1080", DesktopDisplayPolicy.resolveResolution(loaded, 2400, 1080));
            assertEquals("1080x2400", DesktopDisplayPolicy.resolveResolution(loaded, 1080, 2400));
            String spec = DesktopLaunchSpec.encode(loaded, "/pid", "/status", "/log", "2400x1080");
            assertTrue(spec.contains("DESKTOP_RESOLUTION=native\n"));
            assertTrue(spec.contains("DESKTOP_WIDTH=2400\nDESKTOP_HEIGHT=1080\n"));
            assertEquals("native", repository.find(desktop.getId()).get().getResolution());

        }
    }

    @Test public void nativePreparationIsNumericAndInvalidLaunchGeometryIsRejected() {
        DesktopDistro desktop = desktop(DesktopRunMode.PROOT, DesktopDe.UBUNTU_GNOME, "native");
        assertEquals("1280x720", DesktopDisplayPolicy.preparationResolution(desktop));
        assertTrue(DesktopLaunchSpec.encode(desktop, "/pid", "/status", "/log").contains("DESKTOP_WIDTH=1280\n"));
        for (String invalid : new String[]{"native", "0x0", "240x320", "1920x200", "9999x1080", "1920x1080;id"})
            assertThrows(invalid, IllegalArgumentException.class,
                () -> DesktopLaunchSpec.encode(desktop, "/pid", "/status", "/log", invalid));
    }

    @Test public void fixedAndCustomValuesRemainFixedAcrossWindowChanges() throws Exception {
        DesktopDistro desktop = desktop(DesktopRunMode.NATIVE, DesktopDe.XFCE4, "1366x768");
        assertEquals("1366x768", DesktopDisplayPolicy.resolveResolution(desktop, 2400, 1080));
        assertArrayEquals(new String[]{"native", "1280x720", "1600x900", "1920x1080", "2560x1440", "1366x768"},
            DesktopDisplayPolicy.resolutions(desktop.getResolution()));
        assertThrows(IllegalArgumentException.class,
            () -> DesktopLaunchSpec.encode(desktop, "/pid", "/status", "/log", "2400x1080"));
    }

    @Test public void refreshCapabilitiesHaveTheSameTruthForBothModes() {
        for (DesktopDe de : DesktopDe.values()) if (de.isCreatable()) {
            assertArrayEquals(de.isGnomeSession() ? new int[]{60} : new int[]{30, 60, 90, 120},
                DesktopDisplayPolicy.refreshRates(de, 120));
            assertEquals(de.isGnomeSession() ? 60 : 120, DesktopDisplayPolicy.effectiveRefreshRate(de, 120));
        }
        assertArrayEquals(new int[]{30, 60, 90, 120, 75}, DesktopDisplayPolicy.refreshRates(DesktopDe.XFCE4, 75));
    }
}
