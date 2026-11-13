package com.termux.ui.desktop.api;
import com.termux.ui.desktop.domain.DesktopSessionState;
import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;
public class DesktopTasksSettingsTest {
    @Test public void themeSwitchRequiresAllActiveStatesToStop() throws IOException {
        for (DesktopSessionState state : DesktopSessionState.values()) {
            if (state.isActive()) assertEquals("desktop_stop_before_theme_change", assertThrows(IOException.class,
                () -> DesktopTasks.requireComponentSwitchStopped(state)).getMessage());
            else DesktopTasks.requireComponentSwitchStopped(state);
        }
    }
    @Test public void stoppingStillBlocksLaunchCommands() throws IOException {
        assertEquals("desktop_stopping", assertThrows(IOException.class,
            () -> DesktopTasks.requireNotStopping(DesktopSessionState.STOPPING)).getMessage());
        for (DesktopSessionState state : DesktopSessionState.values())
            if (state != DesktopSessionState.STOPPING) DesktopTasks.requireNotStopping(state);
    }
}
