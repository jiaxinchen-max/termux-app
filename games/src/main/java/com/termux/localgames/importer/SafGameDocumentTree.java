package com.termux.localgames.importer;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** DocumentsContract adapter that never writes to the selected tree. */
public final class SafGameDocumentTree implements GameDocumentTree {

    private static final String[] PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE
    };

    private final ContentResolver resolver;
    private final Uri treeUri;
    private final String rootDocumentId;

    public SafGameDocumentTree(ContentResolver resolver, Uri treeUri) throws IOException {
        this.resolver = resolver;
        this.treeUri = SafGameRootUri.treeUri(treeUri);
        rootDocumentId = SafGameRootUri.rootDocumentId(treeUri);
    }

    @Override
    public GameDocument root() throws IOException {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocumentId);
        return querySingle(documentUri, rootDocumentId);
    }

    @Override
    public List<GameDocument> listChildren(GameDocument directory) throws IOException {
        if (!directory.isDirectory()) throw new IOException("document_is_not_directory");
        Uri directoryUri = Uri.parse(directory.getUri());
        String documentId;
        try {
            documentId = DocumentsContract.getDocumentId(directoryUri);
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_document_uri", error);
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId);
        List<GameDocument> children = new ArrayList<>();
        try (Cursor cursor = resolver.query(childrenUri, PROJECTION, null, null, null)) {
            if (cursor == null) throw new IOException("document_provider_returned_null_cursor");
            while (cursor.moveToNext()) {
                String childId = required(cursor, 0, "document_id");
                String name = required(cursor, 1, "display_name");
                String mime = required(cursor, 2, "mime_type");
                long size = cursor.isNull(3) ? -1 : Math.max(-1, cursor.getLong(3));
                Uri childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId);
                children.add(new GameDocument(childUri.toString(), name,
                    DocumentsContract.Document.MIME_TYPE_DIR.equals(mime), size));
            }
        } catch (SecurityException error) {
            throw new IOException("saf_permission_lost", error);
        } catch (RuntimeException error) {
            throw new IOException("document_provider_query_failed", error);
        }
        return children;
    }

    @Override
    public InputStream open(GameDocument file) throws IOException {
        if (file.isDirectory()) throw new IOException("cannot_open_directory");
        try {
            InputStream input = resolver.openInputStream(Uri.parse(file.getUri()));
            if (input == null) throw new FileNotFoundException("document_provider_returned_null_stream");
            return input;
        } catch (SecurityException error) {
            throw new IOException("saf_permission_lost", error);
        }
    }

    private GameDocument querySingle(Uri documentUri, String fallbackName) throws IOException {
        try (Cursor cursor = resolver.query(documentUri, PROJECTION, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("document_provider_missing_root");
            }
            String name = cursor.isNull(1) ? fallbackName : cursor.getString(1);
            String mime = required(cursor, 2, "mime_type");
            long size = cursor.isNull(3) ? -1 : Math.max(-1, cursor.getLong(3));
            return new GameDocument(documentUri.toString(), name,
                DocumentsContract.Document.MIME_TYPE_DIR.equals(mime), size);
        } catch (SecurityException error) {
            throw new IOException("saf_permission_lost", error);
        } catch (RuntimeException error) {
            throw new IOException("document_provider_query_failed", error);
        }
    }

    private static String required(Cursor cursor, int index, String field) throws IOException {
        if (cursor.isNull(index)) throw new IOException("missing_document_field:" + field);
        String value = cursor.getString(index);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("invalid_document_field:" + field);
        }
        return value;
    }
}
