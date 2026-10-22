package com.termux.ui.activity;

import androidx.annotation.NonNull;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads parts of a (possibly multi-megabyte) launch log with their absolute line numbers: the last
 * lines of the file ({@link #read}), and the lines that precede a window already read
 * ({@link #readBefore}), so the viewer can page backwards while scrolling.
 *
 * <p>Memory stays bounded regardless of the file size. {@link #read} streams the raw bytes once,
 * counting lines and remembering the start offset of the last {@code maxLines} of them, then decodes
 * only that window. {@link #readBefore} scans backwards over raw bytes to find where the requested
 * page starts and decodes only that page. Individual lines are cut at {@link #MAX_LINE_BYTES} so a
 * single huge line cannot exhaust memory. Blocking I/O: call it off the UI thread.</p>
 */
final class GameLogTailReader {

    static final int DEFAULT_MAX_LINES = 5000;
    static final int MAX_LINE_BYTES = 2048;
    static final String ELLIPSIS = "…";
    private static final int BUFFER_BYTES = 64 * 1024;

    /** The tail window: {@code lines} are the last lines of the file, {@code totalLines} counts all. */
    static final class Result {
        @NonNull final List<GameLogLine> lines;
        final int totalLines;
        /** Byte offset in the file where the first line of {@link #lines} starts (0 when it is line 1). */
        final long startOffset;

        Result(@NonNull List<GameLogLine> lines, int totalLines, long startOffset) {
            this.lines = lines;
            this.totalLines = totalLines;
            this.startOffset = startOffset;
        }

        /** True when older lines were left out of {@link #lines}. */
        boolean isTruncated() {
            return lines.size() < totalLines;
        }
    }

    /** A page of lines that precede an already-read window. */
    static final class Page {
        @NonNull final List<GameLogLine> lines;
        /** Byte offset where the first line of {@link #lines} starts; 0 means the file start was reached. */
        final long startOffset;

        Page(@NonNull List<GameLogLine> lines, long startOffset) {
            this.lines = lines;
            this.startOffset = startOffset;
        }
    }

    private GameLogTailReader() { }

    @NonNull
    static Result read(@NonNull File file, int maxLines) throws IOException {
        if (maxLines < 1) throw new IllegalArgumentException("maxLines must be positive");
        // One slot more than the window: the provisional start after a trailing newline must not
        // overwrite the start of the window's first line.
        int slots = maxLines + 1;
        long[] starts = new long[slots];
        long length;
        int lineCount = 0;
        long lastStart = 0L;
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER_BYTES)) {
            byte[] buffer = new byte[BUFFER_BYTES];
            long offset = 0L;
            starts[0] = 0L;
            lineCount = 1; // provisional first line; dropped below when the file is empty
            lastStart = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                for (int i = 0; i < read; i++) {
                    if (buffer[i] == '\n') {
                        lastStart = offset + i + 1;
                        starts[lineCount % slots] = lastStart;
                        lineCount++;
                    }
                }
                offset += read;
            }
            length = offset;
        }
        // The provisional/last start is not a line when nothing follows it (empty file, or a
        // trailing newline).
        if (lastStart >= length) lineCount--;
        if (lineCount <= 0) return new Result(Collections.<GameLogLine>emptyList(), 0, 0L);

        int first = Math.max(0, lineCount - maxLines);
        long windowStart = starts[first % slots];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            List<GameLogLine> lines = decode(raf, windowStart, length, first + 1, lineCount - first);
            return new Result(lines, lineCount, windowStart);
        }
    }

    /**
     * Reads up to {@code maxLines} lines that end right before {@code beforeOffset}, which must be the
     * start offset of a line (a {@link Result#startOffset} or {@link Page#startOffset}).
     * {@code nextLineNumber} is the absolute number of the line starting at {@code beforeOffset}; the
     * returned lines are numbered backwards from it. An offset that no longer matches the file (it
     * was rewritten or shortened) is an {@link IOException}.
     */
    @NonNull
    static Page readBefore(@NonNull File file, long beforeOffset, int nextLineNumber, int maxLines)
            throws IOException {
        if (maxLines < 1) throw new IllegalArgumentException("maxLines must be positive");
        if (beforeOffset <= 0L) return new Page(Collections.<GameLogLine>emptyList(), 0L);
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            if (beforeOffset > raf.length()) throw new IOException("log is shorter than expected");
            raf.seek(beforeOffset - 1);
            if (raf.read() != '\n') throw new IOException("log changed");

            // Scan backwards over the bytes before the terminating newline, counting the newlines
            // that end earlier lines; the page starts right after the maxLines-th one.
            long scan = beforeOffset - 1;
            int found = 0;
            long pageStart = 0L;
            boolean limited = false;
            byte[] buffer = new byte[BUFFER_BYTES];
            scanning:
            while (scan > 0) {
                int len = (int) Math.min(buffer.length, scan);
                long blockStart = scan - len;
                raf.seek(blockStart);
                raf.readFully(buffer, 0, len);
                for (int i = len - 1; i >= 0; i--) {
                    if (buffer[i] == '\n' && ++found == maxLines) {
                        pageStart = blockStart + i + 1;
                        limited = true;
                        break scanning;
                    }
                }
                scan = blockStart;
            }
            int count = limited ? maxLines : found + 1;
            int firstNumber = nextLineNumber - count;
            if (firstNumber < 1) throw new IOException("log changed");
            return new Page(decode(raf, pageStart, beforeOffset, firstNumber, count), pageStart);
        }
    }

    /**
     * Decodes the lines in {@code [start, end)}, numbering them from {@code firstNumber}; at most
     * {@code expected} lines, and a last line without a newline is kept.
     */
    @NonNull
    private static List<GameLogLine> decode(@NonNull RandomAccessFile raf, long start, long end,
                                            int firstNumber, int expected) throws IOException {
        List<GameLogLine> lines = new ArrayList<>(expected);
        raf.seek(start);
        long remaining = end - start;
        byte[] buffer = new byte[BUFFER_BYTES];
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        boolean cut = false;
        int number = firstNumber;
        while (remaining > 0 && lines.size() < expected) {
            int read = raf.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) break;
            remaining -= read;
            for (int i = 0; i < read && lines.size() < expected; i++) {
                byte b = buffer[i];
                if (b == '\n') {
                    lines.add(new GameLogLine(number++, text(line, cut)));
                    line.reset();
                    cut = false;
                } else if (line.size() < MAX_LINE_BYTES) {
                    line.write(b);
                } else {
                    cut = true;
                }
            }
        }
        if (lines.size() < expected && (line.size() > 0 || cut)) {
            lines.add(new GameLogLine(number, text(line, cut)));
        }
        return lines;
    }

    @NonNull
    private static String text(@NonNull ByteArrayOutputStream line, boolean cut) {
        String text = new String(line.toByteArray(), StandardCharsets.UTF_8);
        int end = text.length();
        if (end > 0 && text.charAt(end - 1) == '\r') text = text.substring(0, end - 1);
        return cut ? text + ELLIPSIS : text;
    }
}
