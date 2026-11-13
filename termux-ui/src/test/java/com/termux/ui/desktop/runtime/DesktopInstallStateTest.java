package com.termux.ui.desktop.runtime;

import org.junit.Test;
import static org.junit.Assert.*;

public class DesktopInstallStateTest {
    @org.junit.Rule public org.junit.rules.TemporaryFolder temporary = new org.junit.rules.TemporaryFolder();

    @Test public void installationIdentitySurvivesPageRecreationWithoutTailTruncation() throws Exception {
        java.io.File file = new java.io.File(temporary.newFolder(), "status.install-task");
        String id = "desktop-install-" + "abcdefghij".repeat(9);
        DesktopInstallState.writeTask(file, id);
        assertEquals(id, DesktopInstallState.readTask(new java.io.File(file.getPath())));
        assertFalse(new java.io.File(file.getPath() + ".tmp").exists());
    }
    @Test public void corruptOrOversizedIdentityFailsClosedInsteadOfStartingAnotherInstall() throws Exception {
        java.io.File file = temporary.newFile();
        java.nio.file.Files.write(file.toPath(), "desktop-install-".concat("x".repeat(130)).getBytes());
        assertThrows(java.io.IOException.class, () -> DesktopInstallState.readTask(file));
        java.nio.file.Files.write(file.toPath(), "not-a-task".getBytes());
        assertThrows(java.io.IOException.class, () -> DesktopInstallState.readTask(file));
    }
    @Test public void preparationBeforeTerminalCreationStillOwnsInstallation() {
        assertTrue(DesktopInstallState.isActive(false, false, true));
    }
    @Test public void hidingWindowDoesNotChangeRealTerminalOwnership() {
        assertTrue(DesktopInstallState.isActive(true, true, false));
    }
    @Test public void finishedOrLostTerminalDoesNotLeavePermanentBusyState() {
        assertFalse(DesktopInstallState.isActive(true, false, true));
        assertFalse(DesktopInstallState.isActive(false, false, false));
    }
}
