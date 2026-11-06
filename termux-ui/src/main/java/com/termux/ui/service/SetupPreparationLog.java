package com.termux.ui.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** Durable output for the component phase, before a setup TerminalSession exists. */
public final class SetupPreparationLog {
    public static final int MAX_TAIL_BYTES = 48 * 1024;
    private final File filesDirectory;
    private final String parentTaskId;
    private String childTaskId;
    private long childOffset;

    public SetupPreparationLog(File filesDirectory, String parentTaskId) {
        this.filesDirectory = filesDirectory;
        this.parentTaskId = parentTaskId;
        file(filesDirectory, parentTaskId);
    }

    public static File file(File filesDirectory, String taskId) {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("setup_task_id_invalid");
        }
        return new File(filesDirectory, "front/runtime/preparation/" + taskId + ".log");
    }

    public void append(String message) throws IOException {
        appendBytes(file(filesDirectory, parentTaskId), (message + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Copies only this parent's selected child, including the package installer's shell output. */
    public void component(String componentId, String taskId) throws IOException {
        if (!taskId.equals(childTaskId)) {
            childTaskId = taskId;
            childOffset = 0;
            append(componentId + " · " + taskId);
        }
        File source = ComponentTaskConsoleLog.file(filesDirectory, taskId);
        if (!source.isFile()) return;
        try (RandomAccessFile input = new RandomAccessFile(source, "r")) {
            long size = input.length();
            if (size < childOffset) childOffset = 0;
            long start = Math.max(childOffset, size - MAX_TAIL_BYTES);
            input.seek(start);
            byte[] bytes = new byte[(int) (size - start)];
            input.readFully(bytes);
            int offset = 0;
            while (offset < bytes.length && (bytes[offset] & 0xc0) == 0x80) offset++;
            appendBytes(file(filesDirectory, parentTaskId), java.util.Arrays.copyOfRange(bytes, offset, bytes.length));
            childOffset = size;
        }
    }

    public static synchronized void failed(File filesDirectory, String taskId, String message)
        throws IOException {
        new SetupPreparationLog(filesDirectory, taskId).append("FAILED · " + message);
        File error = errorFile(filesDirectory, taskId);
        try (FileOutputStream output = new FileOutputStream(error)) {
            output.write(message.getBytes(StandardCharsets.UTF_8));
        }
    }

    public static synchronized String failure(File filesDirectory, String taskId) throws IOException {
        return readTail(errorFile(filesDirectory, taskId));
    }

    public static String readTail(File file) throws IOException {
        if (!file.isFile()) return "";
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long size = input.length();
            long start = Math.max(0, size - MAX_TAIL_BYTES);
            input.seek(start);
            byte[] bytes = new byte[(int) (size - start)];
            input.readFully(bytes);
            int offset = 0;
            // A tail can start inside a UTF-8 character.
            while (offset < bytes.length && (bytes[offset] & 0xc0) == 0x80) offset++;
            return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
        }
    }

    private static File errorFile(File filesDirectory, String taskId) {
        return new File(file(filesDirectory, taskId).getPath() + ".error");
    }

    private static synchronized void appendBytes(File file, byte[] bytes) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("setup_log_directory_failed");
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(bytes);
        }
    }
}
