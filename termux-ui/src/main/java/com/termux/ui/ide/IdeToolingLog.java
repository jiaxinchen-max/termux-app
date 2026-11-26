package com.termux.ui.ide;

import java.io.*;

/** Two bounded files shared by RPC output and stderr. */
final class IdeToolingLog implements Closeable {
    private final File directory;
    private OutputStream output;
    private long size;
    private boolean closed;
    IdeToolingLog(File directory) throws IOException {
        this.directory = directory;
        File current = new File(directory, "output.log");
        size = current.length(); output = new FileOutputStream(current, true);
    }
    synchronized void write(byte[] bytes, int length) throws IOException {
        if (closed) return;
        if (size + length > 2 * 1024 * 1024) {
            output.close();
            File previous = new File(directory, "output.previous.log"), current = new File(directory, "output.log");
            if (previous.exists() && !previous.delete()) throw new IOException("Cannot rotate tooling log");
            if (!current.renameTo(previous)) throw new IOException("Cannot rotate tooling log");
            output = new FileOutputStream(current); size = 0;
        }
        // A single protocol log notification may itself be larger than the log budget.
        int count = Math.min(length, 2 * 1024 * 1024);
        output.write(bytes, length - count, count); output.flush(); size += count;
    }
    @Override public synchronized void close() throws IOException { closed = true; output.close(); }
}
