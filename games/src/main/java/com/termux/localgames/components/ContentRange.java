package com.termux.localgames.components;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ContentRange {

    private static final Pattern PATTERN = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");

    final long start;
    final long end;
    final long total;

    private ContentRange(long start, long end, long total) {
        this.start = start;
        this.end = end;
        this.total = total;
    }

    static ContentRange parse(String value) throws ComponentDownloadException {
        Matcher matcher = value == null ? null : PATTERN.matcher(value.trim());
        if (matcher == null || !matcher.matches()) {
            throw new ComponentDownloadException("invalid_content_range",
                "Missing or invalid Content-Range");
        }
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            long total = Long.parseLong(matcher.group(3));
            if (start < 0 || end < start || total <= end) {
                throw new ComponentDownloadException("invalid_content_range",
                    "Invalid Content-Range bounds");
            }
            return new ContentRange(start, end, total);
        } catch (NumberFormatException e) {
            throw new ComponentDownloadException("invalid_content_range",
                "Invalid Content-Range number", e);
        }
    }

    long length() {
        return end - start + 1;
    }
}
