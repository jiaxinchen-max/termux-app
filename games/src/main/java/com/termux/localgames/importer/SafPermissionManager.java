package com.termux.localgames.importer;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;

/** Creates the tree picker contract and owns persisted read-permission checks. */
public final class SafPermissionManager {

    private SafPermissionManager() {}

    public static Intent createOpenTreeIntent() {
        return new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
    }

    public static void persistReadPermission(ContentResolver resolver, Uri uri, int resultFlags) {
        if ((resultFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
            throw new SecurityException("saf_read_permission_not_granted");
        }
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!hasPersistedReadPermission(resolver, uri)) {
            throw new SecurityException("saf_read_permission_not_persisted");
        }
    }

    public static boolean hasPersistedReadPermission(ContentResolver resolver, Uri uri) {
        Uri treeUri = SafGameRootUri.treeUri(uri);
        for (UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.isReadPermission() && permission.getUri().equals(treeUri)) return true;
        }
        return false;
    }
}
