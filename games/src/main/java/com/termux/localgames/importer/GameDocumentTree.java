package com.termux.localgames.importer;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Read-only directory tree used by the scanner. */
public interface GameDocumentTree {
    GameDocument root() throws IOException;
    List<GameDocument> listChildren(GameDocument directory) throws IOException;
    InputStream open(GameDocument file) throws IOException;
}
