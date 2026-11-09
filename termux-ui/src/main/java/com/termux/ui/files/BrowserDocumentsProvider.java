package com.termux.ui.files;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.webkit.MimeTypeMap;

import com.termux.ui.data.GameStoragePaths;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Locale;

/** Explicit read grants only. No browsable global roots and no write operations. */
public final class BrowserDocumentsProvider extends DocumentsProvider {
    private static final String[] COLUMNS = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED, DocumentsContract.Document.COLUMN_FLAGS
    };

    public static String authority(Context context) { return context.getPackageName() + ".game-browser.documents"; }

    public static Intent openIntent(Context context, File file) throws IOException {
        File canonical = checked(context, file.getCanonicalPath());
        Uri uri;
        Uri grant;
        int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
        if (canonical.isDirectory()) {
            Uri tree = DocumentsContract.buildTreeDocumentUri(authority(context), canonical.getPath());
            uri = DocumentsContract.buildDocumentUriUsingTree(tree, canonical.getPath());
            grant = tree;
            flags |= Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
        } else {
            uri = DocumentsContract.buildDocumentUri(authority(context), canonical.getPath());
            grant = uri;
        }
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(canonical)).addFlags(flags);
        intent.setClipData(ClipData.newRawUri(canonical.getName(), grant));
        return intent;
    }

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        return new MatrixCursor(projection == null ? new String[] { DocumentsContract.Root.COLUMN_ROOT_ID } : projection);
    }

    @Override public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        File file = resolve(documentId);
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        include(cursor, file);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parentId, String[] projection, String sortOrder)
            throws FileNotFoundException {
        File parent = resolve(parentId);
        if (!parent.isDirectory()) throw new FileNotFoundException("Not a directory");
        File[] files = parent.listFiles();
        if (files == null) throw new FileNotFoundException("Directory is unreadable");
        MatrixCursor cursor = new MatrixCursor(projection == null ? COLUMNS : projection);
        for (File child : files) {
            try {
                // Do not expose symlink targets outside this granted subtree or any protected root.
                File checked = resolve(child.getPath());
                if (BrowserFileOperations.contains(parent, checked)) include(cursor, checked);
            } catch (FileNotFoundException ignored) { /* Not a readable member of this directory. */ }
        }
        return cursor;
    }

    @Override public boolean isChildDocument(String parentId, String documentId) {
        try { return BrowserFileOperations.contains(resolve(parentId), resolve(documentId)); }
        catch (FileNotFoundException e) { return false; }
    }

    @Override public ParcelFileDescriptor openDocument(String documentId, String mode,
            CancellationSignal cancellation) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only document");
        if (cancellation != null) cancellation.throwIfCanceled();
        File file = resolve(documentId);
        if (!file.isFile()) throw new FileNotFoundException("Not a regular file");
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private File resolve(String id) throws FileNotFoundException {
        try { return checked(getContext(), id); }
        catch (IOException | SecurityException e) {
            FileNotFoundException error = new FileNotFoundException("Document unavailable: " + id);
            error.initCause(e);
            throw error;
        }
    }

    private static File checked(Context context, String id) throws IOException {
        if (id == null || !id.startsWith("/")) throw new IOException("Invalid document id");
        File file = new File(id);
        File canonical = file.getCanonicalFile();
        // Grants identify this path. A replaced link must not silently redirect that grant.
        if (!id.equals(canonical.getPath())) throw new IOException("Non-canonical document");
        File[] roots = { context.getFilesDir(), new GameStoragePaths(context.getFilesDir()).getImportedGamesDirectory(),
            Environment.getExternalStorageDirectory() };
        boolean inside = false;
        for (File root : roots) {
            File resolved = root.getCanonicalFile();
            if (canonical.equals(resolved)) throw new IOException("Protected root");
            if (BrowserFileOperations.contains(resolved, canonical)) inside = true;
        }
        if (!inside || !canonical.exists()) throw new IOException("Document outside browser or missing");
        if (!canonical.isDirectory() && !canonical.isFile()) throw new IOException("Unsupported document");
        return canonical;
    }

    private static String mime(File file) {
        if (file.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : MimeTypeMap.getSingleton().getMimeTypeFromExtension(
            name.substring(dot + 1).toLowerCase(Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }

    private static void include(MatrixCursor cursor, File file) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            Object value = null;
            switch (column) {
                case DocumentsContract.Document.COLUMN_DOCUMENT_ID: value = file.getPath(); break;
                case DocumentsContract.Document.COLUMN_DISPLAY_NAME: value = file.getName(); break;
                case DocumentsContract.Document.COLUMN_MIME_TYPE: value = mime(file); break;
                case DocumentsContract.Document.COLUMN_SIZE: value = file.isDirectory() ? null : file.length(); break;
                case DocumentsContract.Document.COLUMN_LAST_MODIFIED: value = file.lastModified(); break;
                case DocumentsContract.Document.COLUMN_FLAGS: value = 0; break;
            }
            row.add(value);
        }
    }
}
