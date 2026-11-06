package com.termux.ui.data;

import java.io.File;

/** Shared private-storage root for the UI features. */
public final class FrontStorage {
    private FrontStorage() { }

    public static File root(File filesDirectory) {
        if (filesDirectory == null) throw new IllegalArgumentException("filesDirectory required");
        return new File(filesDirectory, "front");
    }
}
