package com.termux.localgames.importer;

import android.net.Uri;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Read-only scanner adapter for a local, app-private game directory. */
public final class FileGameDocumentTree implements GameDocumentTree {

    private final File root;

    public FileGameDocumentTree(File root) throws IOException {
        if (root == null) throw new IOException("local_root_missing");
        this.root = root.getCanonicalFile();
        if (!this.root.isDirectory() || !this.root.canRead()) {
            throw new IOException("local_root_unavailable");
        }
    }

    @Override
    public GameDocument root() {
        return document(root);
    }

    @Override
    public List<GameDocument> listChildren(GameDocument directory) throws IOException {
        File parent = fileFor(directory);
        if (!parent.isDirectory()) throw new IOException("document_is_not_directory");
        File[] files = parent.listFiles();
        if (files == null) throw new IOException("local_directory_unreadable");
        List<GameDocument> result = new ArrayList<>();
        for (File child : files) {
            File canonical = child.getCanonicalFile();
            if (isWithinRoot(canonical)) result.add(document(canonical));
        }
        return result;
    }

    @Override
    public InputStream open(GameDocument file) throws IOException {
        File target = fileFor(file);
        if (target.isDirectory()) throw new IOException("cannot_open_directory");
        return new FileInputStream(target);
    }

    private GameDocument document(File file) {
        String name = file.equals(root) ? root.getName() : file.getName();
        if (name == null || name.trim().isEmpty()) name = root.getPath();
        return new GameDocument(Uri.fromFile(file).toString(), name, file.isDirectory(),
            file.isDirectory() ? -1 : Math.max(0, file.length()));
    }

    private File fileFor(GameDocument document) throws IOException {
        Uri uri = Uri.parse(document.getUri());
        if (!"file".equals(uri.getScheme()) || uri.getPath() == null) {
            throw new IOException("invalid_local_document_uri");
        }
        File file = new File(uri.getPath()).getCanonicalFile();
        if (!isWithinRoot(file)) throw new IOException("local_document_outside_root");
        return file;
    }

    private boolean isWithinRoot(File candidate) {
        String rootPath = root.getPath();
        return candidate.equals(root) || candidate.getPath().startsWith(rootPath + File.separator);
    }
}
