package com.termux.ui.platform;

import android.os.Environment;

import java.io.File;

/**
 * Where a user-triggered base-environment backup is written to / read from. Deliberately NOT under
 * the app's files directory (unlike every other path of {@link com.termux.ui.data.GameStoragePaths})
 * -- the whole point of this feature is to survive an app data clear or uninstall, so it has to live
 * on real external/shared storage (requires MANAGE_EXTERNAL_STORAGE, already declared in
 * AndroidManifest.xml; see PermissionUtils). Single fixed name, not one per backup: only the most
 * recent backup is ever kept (see RootfsBackupForegroundService) -- the backup/restore UI is a
 * straight overwrite, not a version picker.
 */
public final class ExternalBackupLocation {
    private ExternalBackupLocation() { }

    public static File directory() {
        return new File(Environment.getExternalStorageDirectory(), "TermuxGames/backups");
    }

    public static File file() {
        return new File(directory(), "games-base-backup.tar.zst");
    }
}
