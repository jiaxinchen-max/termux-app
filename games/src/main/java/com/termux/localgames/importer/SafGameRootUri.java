package com.termux.localgames.importer;

import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.IOException;

/** Encodes a selected directory beneath a persisted SAF tree without losing its grant root. */
public final class SafGameRootUri {

    private static final String ROOT_DOCUMENT_ID = "termuxGameRoot";

    private SafGameRootUri() {}

    public static Uri forDocument(Uri treeUri, Uri documentUri) throws IOException {
        try {
            return treeUri(treeUri).buildUpon()
                .appendQueryParameter(ROOT_DOCUMENT_ID,
                    DocumentsContract.getDocumentId(documentUri))
                .build();
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_saf_game_root", error);
        }
    }

    public static Uri treeUri(Uri gameRootUri) {
        return gameRootUri.buildUpon().clearQuery().fragment(null).build();
    }

    public static String rootDocumentId(Uri gameRootUri) throws IOException {
        String selected = gameRootUri.getQueryParameter(ROOT_DOCUMENT_ID);
        if (selected != null && !selected.trim().isEmpty()) return selected;
        try {
            return DocumentsContract.getTreeDocumentId(treeUri(gameRootUri));
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_tree_uri", error);
        }
    }
}
