package com.termux.localgames.runtime;

import com.termux.localgames.domain.LaunchEvent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Replays complete JSONL lines; a non-newline-terminated tail is left for the next poll. */
public final class LaunchEventLogReader {

    private static final int MAX_LOG_BYTES = 8 * 1024 * 1024;

    private final LaunchEventJsonParser parser;

    public LaunchEventLogReader() { this(new LaunchEventJsonParser()); }

    LaunchEventLogReader(LaunchEventJsonParser parser) { this.parser = parser; }

    public List<LaunchEvent> readAfter(File file, long sequence) throws IOException {
        if (file == null || !file.isFile()) return Collections.emptyList();
        List<LaunchEvent> events = new ArrayList<>();
        byte[] content;
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384];
            int count;
            int total = 0;
            while ((count = input.read(buffer)) != -1) {
                total += count;
                if (total > MAX_LOG_BYTES) throw new IOException("launch_event_log_too_large");
                output.write(buffer, 0, count);
            }
            content = output.toByteArray();
        }
        int completeLength = content.length;
        while (completeLength > 0 && content[completeLength - 1] != '\n') completeLength--;
        if (completeLength == 0) return Collections.emptyList();
        String complete = new String(content, 0, completeLength, StandardCharsets.UTF_8);
        for (String line : complete.split("\n", -1)) {
            if (line.isEmpty()) continue;
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            LaunchEvent event = parser.parse(line);
            if (event.getSequence() > sequence) events.add(event);
        }
        return Collections.unmodifiableList(events);
    }
}
