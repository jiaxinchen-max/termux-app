package com.termux.app.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Button;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class StartEntryClientTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private Activity activity;
    private Button button;
    private File config;
    private StartEntry entries;
    private final List<File> launched = new ArrayList<>();
    private Consumer<File> picker;

    @Before public void setup() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        button = new Button(activity);
        button.setText("start x11 server");
        activity.setContentView(button);
        config = new File(temp.newFolder(), "scripts.json");
        entries = new StartEntry(config);
        attach();
    }

    private void attach() {
        new StartEntryClient(activity, button, entries, new StartEntryClient.Host() {
            @Override public void launch(File script) { launched.add(script); }
            @Override public void pick(android.view.View anchor, Consumer<File> selected) { picker = selected; }
        });
    }

    private File addThroughMenu(String name) throws Exception {
        File script = temp.newFile(name);
        assertTrue(script.setExecutable(true));
        assertTrue(button.performLongClick());
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNotNull(picker);
        picker.accept(script);
        return script;
    }

    @Test public void emptyClickOpensSelectionWithoutStartingAnything() {
        button.performClick();
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        assertTrue(launched.isEmpty());
    }

    @Test public void longPressAddsScriptThenClickRunsSelectedFileOnce() throws Exception {
        File script = addThroughMenu("desktop ' one.sh");
        assertEquals(script.getName(), button.getText().toString());
        assertTrue(launched.isEmpty());
        button.performClick();
        assertEquals(java.util.Collections.singletonList(script), launched);
    }

    @Test public void changingSelectionAndRecreatingClientKeepsSelectedScript() throws Exception {
        File first = addThroughMenu("first.sh");
        addThroughMenu("second.sh");
        button.performLongClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.getListView().isItemChecked(1));
        assertTrue(dialog.getListView().getAdapter().getView(1, null, dialog.getListView()) instanceof android.widget.Checkable);
        dialog.getListView().performItemClick(dialog.getListView().getChildAt(0), 0, 0);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        entries = new StartEntry(config);
        attach();
        assertEquals(2, entries.getStartItemList().size());
        assertEquals(first.getName(), button.getText().toString());
        button.performClick();
        assertEquals(java.util.Collections.singletonList(first), launched);
        entries.loadStartItems();
        assertEquals(2, entries.getStartItemList().size());
    }

    @Test public void cancelDoesNotChangeSelectionOrLaunch() throws Exception {
        File script = addThroughMenu("cancel.sh");
        button.performLongClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(script.getName(), button.getText().toString());
        assertTrue(launched.isEmpty());
    }

    @Test public void removingSelectedItemKeepsFileAndRequiresAnotherSelection() throws Exception {
        File script = addThroughMenu("remove.sh");
        button.performLongClick();
        ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue(script.isFile());
        assertNull(entries.getSelectedEntry());
        button.performClick();
        assertTrue(launched.isEmpty());
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    }

    @Test public void missingScriptDoesNotRunAnotherCommand() throws Exception {
        File script = addThroughMenu("missing.sh");
        assertTrue(script.delete());
        button.performClick();
        assertTrue(launched.isEmpty());
    }

    @Test @Config(shadows = com.termux.x11.X11DisplaySynchronizationTest.NativeView.class)
    public void actualIdleLayoutsExposeLauncherOnlyForTerminalMode() {
        com.termux.x11.TermuxScreenView terminal = new com.termux.x11.TermuxScreenView(activity,
            com.termux.x11.TermuxScreenView.IdlePresentation.TERMINAL);
        terminal.setTerminalIdleActions(() -> {}, () -> {});
        Button start = terminal.findViewById(com.termux.x11.R.id.help_button);
        assertNotNull(start);
        new StartEntryClient(activity, start, entries, new StartEntryClient.Host() {
            @Override public void launch(File script) { launched.add(script); }
            @Override public void pick(android.view.View anchor, Consumer<File> selected) { picker = selected; }
        });
        assertTrue(start.performLongClick());
        assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
        assertTrue(launched.isEmpty());
        com.termux.x11.TermuxScreenView ui = new com.termux.x11.TermuxScreenView(activity,
            com.termux.x11.TermuxScreenView.IdlePresentation.UI);
        assertNull(ui.findViewById(com.termux.x11.R.id.help_button));
    }
}
