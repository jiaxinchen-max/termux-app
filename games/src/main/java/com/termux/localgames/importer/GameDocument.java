package com.termux.localgames.importer;

import java.util.Objects;

/** Provider-neutral metadata for one SAF document. */
public final class GameDocument {

    private final String uri;
    private final String name;
    private final boolean directory;
    private final long size;

    public GameDocument(String uri, String name, boolean directory, long size) {
        this.uri = requireText(uri, "uri");
        this.name = requireText(name, "name");
        this.directory = directory;
        if (size < -1) throw new IllegalArgumentException("size must be -1 or non-negative");
        this.size = size;
    }

    public String getUri() { return uri; }
    public String getName() { return name; }
    public boolean isDirectory() { return directory; }
    public long getSize() { return size; }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.trim().isEmpty()) throw new IllegalArgumentException(name + " must not be empty");
        return value;
    }
}
