package com.termux.ui.importer;

import android.content.ContentResolver;
import android.net.Uri;

import com.termux.ui.data.GameAccessProbe;
import com.termux.ui.data.GameAccessState;
import com.termux.ui.domain.Game;

import java.io.IOException;
import java.io.File;

/** Android adapter that recomputes access; the result is never persisted into Game. */
public final class SafGameAccessProbe implements GameAccessProbe {

    private final ContentResolver resolver;

    public SafGameAccessProbe(ContentResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public GameAccessState check(Game game) {
        Uri rootUri;
        try {
            rootUri = Uri.parse(game.getRootUri());
        } catch (RuntimeException error) {
            return GameAccessState.PROVIDER_UNAVAILABLE;
        }
        if ("file".equals(rootUri.getScheme())) {
            String path = rootUri.getPath();
            if (path == null) return GameAccessState.PROVIDER_UNAVAILABLE;
            File root = new File(path);
            return root.isDirectory() && root.canRead()
                ? GameAccessState.ACCESSIBLE : GameAccessState.PROVIDER_UNAVAILABLE;
        }
        if (!SafPermissionManager.hasPersistedReadPermission(resolver, rootUri)) {
            return GameAccessState.PERMISSION_LOST;
        }
        try {
            return new SafGameDocumentTree(resolver, rootUri).root().isDirectory()
                ? GameAccessState.ACCESSIBLE : GameAccessState.PROVIDER_UNAVAILABLE;
        } catch (IOException | RuntimeException error) {
            return GameAccessState.PROVIDER_UNAVAILABLE;
        }
    }
}
