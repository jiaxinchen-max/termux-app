package com.termux.app.terminal;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import com.termux.R;
import com.termux.app.TermuxActivity;

import java.io.File;
import java.io.IOException;
import java.util.function.Consumer;

/** Terminal-mode startup scripts; executing their contents is the terminal's responsibility. */
public final class StartEntryClient {
    public interface Host {
        void launch(File script);
        void pick(View anchor, Consumer<File> selected);
    }

    private final Activity activity;
    private final Button button;
    private final StartEntry entries;
    private final Host host;
    private final CharSequence emptyLabel;

    public StartEntryClient(TermuxActivity activity, Button button) {
        this(activity, button, new StartEntry(ToolboxConfigFiles.file(".startItemEntries")), new Host() {
            private FileBrowser browser;

            @Override public void launch(File script) { activity.launchX11Script(script); }

            @Override public void pick(View anchor, Consumer<File> selected) {
                browser = new FileBrowser(activity, info -> {
                    browser.hideFileBrowser();
                    selected.accept(new File(info.getPath()));
                });
                browser.init();
                browser.showFileBrowser(anchor);
            }
        });
    }

    public StartEntryClient(Activity activity, Button button, StartEntry entries, Host host) {
        this.activity = activity;
        this.button = button;
        this.entries = entries;
        this.host = host;
        emptyLabel = button.getText();
        try { entries.loadStartItems(); } catch (IOException error) { showError(error); }
        updateLabel();
        button.setOnClickListener(view -> launch());
        button.setOnLongClickListener(view -> {
            showScripts();
            return true;
        });
    }

    private void launch() {
        StartEntry.Entry selected = entries.getSelectedEntry();
        if (selected == null) {
            showScripts();
            return;
        }
        File file = new File(selected.getPath());
        if (!file.isFile()) {
            Toast.makeText(activity, activity.getString(R.string.no_such_file) + ": " + file.getName(), Toast.LENGTH_LONG).show();
            return;
        }
        if (!file.canExecute()) {
            Toast.makeText(activity, activity.getString(R.string.not_executable) + ": " + file.getName(), Toast.LENGTH_LONG).show();
            return;
        }
        host.launch(file);
    }

    private void showScripts() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
            .setTitle(R.string.x11_startup_scripts)
            .setSingleChoiceItems(new StartEntryArrayAdapter(activity, entries.getStartItemList()),
                entries.getCurrentStartItemIdx(), (window, index) -> {
                    entries.setCurrentStartItemIdx(index);
                    save();
                    window.dismiss();
                })
            .setPositiveButton(R.string.x11_startup_add, (window, which) -> host.pick(button, this::addScript))
            .setNegativeButton(android.R.string.cancel, null);
        if (entries.getSelectedEntry() != null) {
            dialog.setNeutralButton(R.string.remove, (window, which) -> {
                entries.deleteStartEntry(entries.getSelectedEntry());
                save();
            });
        }
        dialog.show();
    }

    private void addScript(File script) {
        StartEntry.Entry item = new StartEntry.Entry();
        item.setPath(script.getAbsolutePath());
        item.setFileName(script.getName());
        item.setIconPath("default");
        entries.addStartEntry(item);
        for (int i = 0; i < entries.getStartItemList().size(); i++) {
            if (script.getAbsolutePath().equals(entries.getStartItemList().get(i).getPath())) {
                entries.setCurrentStartItemIdx(i);
                break;
            }
        }
        save();
    }

    private void save() {
        try { entries.saveStartItems(); } catch (IOException error) { showError(error); }
        updateLabel();
    }

    private void updateLabel() {
        StartEntry.Entry selected = entries.getSelectedEntry();
        button.setText(selected == null ? emptyLabel : selected.getFileName());
    }

    private void showError(IOException error) {
        Toast.makeText(activity, error.getMessage(), Toast.LENGTH_LONG).show();
    }
}
