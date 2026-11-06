package com.termux.ui.desktop.api;

import com.termux.ui.desktop.domain.DesktopDistro;
import com.termux.ui.desktop.domain.DesktopGlMode;
import com.termux.ui.desktop.domain.DesktopRunMode;
import com.termux.ui.desktop.domain.DesktopSessionState;
import com.termux.ui.desktop.domain.DesktopUsbStorageMode;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class DesktopTasksSettingsTest {
    private DesktopDistro desktop() {
        return new DesktopDistro("test", "Desktop", DesktopRunMode.NATIVE, "",
            DesktopGlMode.SYSTEM, "1280x720", 60);
    }

    @Test public void activeRendererChangeRequiresStop() {
        DesktopDistro old = desktop();
        IOException error = assertThrows(IOException.class, () ->
            DesktopTasks.requireEditableSettings(old, old.withGlMode(DesktopGlMode.ZINK),
                DesktopSessionState.RUNNING));
        assertEquals("desktop_settings_stop_required", error.getMessage());
    }

    @Test public void startingResolutionChangeRequiresStop() {
        DesktopDistro old = desktop();
        assertThrows(IOException.class, () -> DesktopTasks.requireEditableSettings(old,
            old.withOutput("1920x1080", 60), DesktopSessionState.STARTING));
    }

    @Test public void activeRefreshChangeRequiresStop() {
        DesktopDistro old = desktop();
        assertThrows(IOException.class, () -> DesktopTasks.requireEditableSettings(old,
            old.withOutput("1280x720", 90), DesktopSessionState.RUNNING));
    }

    @Test public void activeRenameAndUnchangedSettingsRemainAllowed() throws IOException {
        DesktopDistro old = desktop();
        DesktopTasks.requireEditableSettings(old, old.withName("Renamed"), DesktopSessionState.RUNNING);
        DesktopTasks.requireEditableSettings(old, old, DesktopSessionState.STARTING);
    }

    @Test public void stoppedAndFailedSessionsCanChooseRenderer() throws IOException {
        DesktopDistro old = desktop();
        DesktopTasks.requireEditableSettings(old, old.withGlMode(DesktopGlMode.ZINK),
            DesktopSessionState.INSTALLED);
        DesktopTasks.requireEditableSettings(old, old.withGlMode(DesktopGlMode.LLVMPIPE),
            DesktopSessionState.FAILED);
    }
    @Test public void usbStorageModeChangeRequiresStoppedSession() throws IOException {
        DesktopDistro old=desktop(),updated=old.withUsbStorage(DesktopUsbStorageMode.READ_ONLY);
        assertThrows(IOException.class,()->DesktopTasks.requireEditableSettings(old,updated,DesktopSessionState.RUNNING));
        assertThrows(IOException.class,()->DesktopTasks.requireEditableSettings(old,updated,DesktopSessionState.STARTING));
        DesktopTasks.requireEditableSettings(old,updated,DesktopSessionState.INSTALLED);
    }

    @Test public void stoppingRejectsAllSettingsIncludingRenameAndUnchangedSave() {
        DesktopDistro old = desktop();
        for (DesktopDistro updated : new DesktopDistro[]{old, old.withName("Renamed"),
            old.withGlMode(DesktopGlMode.ZINK), old.withOutput("1920x1080", 90),
            old.withUsbStorage(DesktopUsbStorageMode.READ_ONLY)}) {
            IOException error = assertThrows(IOException.class, () ->
                DesktopTasks.requireEditableSettings(old, updated, DesktopSessionState.STOPPING));
            assertEquals("desktop_settings_stop_required", error.getMessage());
        }
    }

    @Test public void stoppingCannotBeOverwrittenByANewStartInstallOrDeviceAction() throws IOException {
        IOException error = assertThrows(IOException.class, () ->
            DesktopTasks.requireNotStopping(DesktopSessionState.STOPPING));
        assertEquals("desktop_stopping", error.getMessage());
        for (DesktopSessionState state : DesktopSessionState.values())
            if (state != DesktopSessionState.STOPPING) DesktopTasks.requireNotStopping(state);
    }
}
