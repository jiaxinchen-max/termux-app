package com.termux.ui.data;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/** Creation metadata is immutable across saves; legacy file times are captured only once. */
public final class RecordCreationTime {
    private RecordCreationTime() { }

    public static long read(Properties properties, File file) throws IOException {
        String value = properties.getProperty("createdAt");
        if (value == null) return Math.max(0, file.lastModified());
        try {
            long time = Long.parseLong(value);
            if (time < 0) throw new NumberFormatException();
            return time;
        } catch (NumberFormatException error) {
            throw new IOException("invalid_creation_time", error);
        }
    }

    public static long forSave(File target, long proposed) throws IOException {
        if (!target.isFile()) return proposed;
        Properties previous = new Properties();
        try (FileInputStream input = new FileInputStream(target)) { previous.load(input); }
        return read(previous, target);
    }
}
