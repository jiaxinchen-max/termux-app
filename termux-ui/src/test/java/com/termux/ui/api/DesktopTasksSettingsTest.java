package com.termux.ui.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import com.termux.ui.domain.DesktopDistro;
import com.termux.ui.domain.DesktopGlMode;
import com.termux.ui.domain.DesktopRunMode;
import com.termux.ui.domain.DesktopSessionState;

import org.junit.Test;
import java.io.IOException;

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
}
