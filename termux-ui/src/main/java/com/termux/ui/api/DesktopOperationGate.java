package com.termux.ui.api;

import java.io.IOException;

/** Serializes archive reservation with the desktop command submission boundary. */
public final class DesktopOperationGate {
    public static final Object LOCK = new Object();
    private static boolean archive;
    private DesktopOperationGate() {}

    public static void requireAvailable() throws IOException {
        if (archive) throw new IOException("desktop_archive_busy");
    }

    public static void reserveArchive() throws IOException {
        requireAvailable();
        archive = true;
    }

    public static void releaseArchive() { archive = false; }
    public static boolean isArchiveActive() { synchronized (LOCK) { return archive; } }
}
