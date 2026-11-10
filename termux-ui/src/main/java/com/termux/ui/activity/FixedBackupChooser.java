package com.termux.ui.activity;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import com.termux.ui.R;
import com.termux.ui.platform.ExternalBackupLocation;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;

/** Chooses a backup version without exposing directory navigation or a save dialog. */
public final class FixedBackupChooser {
    private FixedBackupChooser() { }
    public static AlertDialog show(Activity activity, String extension, Consumer<File> selected) throws IOException {
        List<File> files = ExternalBackupLocation.archives(activity, extension);
        com.google.android.material.dialog.MaterialAlertDialogBuilder builder = new FrostedDialogBuilder(activity)
            .setTitle(R.string.backup_select_archive)
            .setNegativeButton(android.R.string.cancel, null);
        if (files.isEmpty()) builder.setMessage(activity.getString(R.string.backup_no_archives, ExternalBackupLocation.directory().getAbsolutePath()));
        else builder.setItems(files.stream().map(File::getName).toArray(String[]::new),
            (dialog, which) -> selected.accept(files.get(which)));
        return builder.show();
    }
}
