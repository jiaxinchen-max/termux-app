package com.termux.ui.desktop.data;

import com.termux.ui.desktop.domain.*;
import com.termux.ui.desktop.runtime.DesktopRendererPolicy;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class UsbSelectionTransactionTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    @Test public void everyDesktopCommitsOnlyPreparedCandidateAndRestoresFailedConfiguration() throws Exception {
        for (DesktopDe de : new DesktopDe[] {DesktopDe.XFCE4, DesktopDe.KWIN_PLASMA,
                DesktopDe.UBUNTU_GNOME, DesktopDe.HYPRLAND}) {
          for (DesktopUsbStorageMode usbMode : new DesktopUsbStorageMode[] {DesktopUsbStorageMode.READ_ONLY, DesktopUsbStorageMode.READ_WRITE}) {
            File root = temp.newFolder(de.getStorageValue() + usbMode.getStorageValue());
            FileDesktopDistroRepository saved = new FileDesktopDistroRepository(new File(root, "records"), new File(root, "home"));
            DesktopDistro original = new DesktopDistro("desktop-usb", "USB", DesktopRunMode.PROOT, "ubuntu-usb-1", de,
                DesktopGlMode.SYSTEM, DesktopRendererPolicy.guestModes(DesktopRunMode.PROOT, de).get(0),
                "1280x720", 60, DesktopUsbStorageMode.OFF);
            saved.save(original);
            File session = new File(root, "session"), added = new File(root, "new-entry");
            Files.write(session.toPath(), new byte[] {1});
            HyprlandSelectionTransaction transaction = new HyprlandSelectionTransaction(new File(root, "pending"), saved, root);
            DesktopDistro enabled = original.withUsbStorage(usbMode);
            transaction.stage(enabled, "desktop-install-failed", Arrays.asList(session, added));
            Files.write(session.toPath(), new byte[] {2}); Files.write(added.toPath(), new byte[] {3});
            assertEquals(DesktopUsbStorageMode.OFF, saved.find(original.getId()).get().getUsbStorage());
            transaction.finish(original.getId(), "desktop-install-failed", false, d -> true);
            assertEquals(DesktopUsbStorageMode.OFF, saved.find(original.getId()).get().getUsbStorage());
            assertArrayEquals(new byte[] {1}, Files.readAllBytes(session.toPath())); assertFalse(added.exists());
            transaction.stage(enabled, "desktop-install-successful", Arrays.asList(session, added));
            Files.write(session.toPath(), new byte[] {4}); Files.write(added.toPath(), new byte[] {5});
            transaction.finish(original.getId(), "desktop-install-successful", true, d -> true);
            assertEquals(usbMode, saved.find(original.getId()).get().getUsbStorage());
            assertArrayEquals(new byte[] {4}, Files.readAllBytes(session.toPath())); assertTrue(added.isFile());
          }
        }
    }
}
