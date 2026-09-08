package com.termux.localgames.runtime;

import com.termux.localgames.components.index.StrictJsonParser;
import com.termux.localgames.domain.LaunchEvent;
import com.termux.localgames.domain.LaunchStage;
import com.termux.localgames.domain.LaunchTaskState;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Strict parser for one launch JSONL record. */
public final class LaunchEventJsonParser {

    private static final Set<String> KEYS = new HashSet<>(Arrays.asList(
        "schemaVersion", "taskId", "sequence", "state", "stage", "progress",
        "timestamp", "pid", "exitCode", "errorCode", "recoverable", "message", "logRef"));

    public LaunchEvent parse(String line) throws IOException {
        if (line == null || line.length() > 65536) throw new IOException("launch_event_too_large");
        Object parsed = new StrictJsonParser(line).parse();
        if (!(parsed instanceof Map)) throw new IOException("launch_event_not_object");
        @SuppressWarnings("unchecked") Map<String, Object> object = (Map<String, Object>) parsed;
        if (!object.keySet().equals(KEYS)) throw new IOException("invalid_launch_event_fields");
        if (number(object, "schemaVersion") != LaunchEvent.SCHEMA_VERSION) {
            throw new IOException("unsupported_launch_event_schema");
        }
        try {
            return new LaunchEvent(text(object, "taskId"), number(object, "sequence"),
                LaunchTaskState.valueOf(text(object, "state")),
                LaunchStage.valueOf(text(object, "stage")),
                checkedInt(number(object, "progress"), "progress"),
                number(object, "timestamp"), number(object, "pid"),
                nullableInt(object.get("exitCode")), text(object, "errorCode"),
                bool(object, "recoverable"), text(object, "message"), text(object, "logRef"));
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid_launch_event", error);
        }
    }

    private static String text(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IOException("invalid_launch_event_text:" + key);
        return (String) value;
    }

    private static long number(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof Long)) throw new IOException("invalid_launch_event_number:" + key);
        return (Long) value;
    }

    private static boolean bool(Map<String, Object> object, String key) throws IOException {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) throw new IOException("invalid_launch_event_boolean:" + key);
        return (Boolean) value;
    }

    private static Integer nullableInt(Object value) throws IOException {
        if (value == null) return null;
        if (!(value instanceof Long)) throw new IOException("invalid_launch_event_exit_code");
        return checkedInt((Long) value, "exitCode");
    }

    private static int checkedInt(long value, String key) throws IOException {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("launch_event_integer_range:" + key);
        }
        return (int) value;
    }
}
