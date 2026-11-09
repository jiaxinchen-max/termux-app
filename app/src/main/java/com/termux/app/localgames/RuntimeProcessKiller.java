package com.termux.app.localgames;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Stops only same-UID processes bound to explicit runtime paths, including their descendants. */
final class RuntimeProcessKiller {
    interface Signal { void send(int pid, boolean freeze) throws IOException; }
    private final File proc;
    private final int ownPid;
    private final int uid;
    private final Signal signal;

    RuntimeProcessKiller(File proc, int ownPid, int uid, Signal signal) {
        this.proc = proc; this.ownPid = ownPid; this.uid = uid; this.signal = signal;
    }

    void kill(List<String> paths) throws IOException {
        Map<Integer, Entry> entries = snapshot(paths);
        Set<Integer> targets = targets(entries);
        // Freeze parents before killing so they cannot fork new workers during tree teardown.
        try {
            for (int pid : targets) signal.send(pid, true);
            Map<Integer, Entry> finalEntries = snapshot(paths);
            for (int pid : targets) {
                Entry current = finalEntries.get(pid);
                if (current != null) current.matched = true;
            }
            targets.addAll(targets(finalEntries));
        } finally {
            IOException failure = null;
            for (int pid : targets) {
                try { signal.send(pid, false); }
                catch (IOException error) { failure = error; }
            }
            if (failure != null) throw failure;
        }
        // This confirms SIGKILL delivery, without a graceful shutdown or a natural-exit wait.
        for (int attempt = 0; attempt < 25; attempt++) {
            Map<Integer, Entry> remaining = snapshot(paths);
            boolean alive = false;
            for (int pid : targets) if (remaining.containsKey(pid)) alive = true;
            if (!alive && targets(remaining).isEmpty()) return;
            for (int pid : targets(remaining)) { targets.add(pid); signal.send(pid, false); }
            try { Thread.sleep(20); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt(); throw new IOException("runtime_force_stop_interrupted", error);
            }
        }
        throw new IOException("runtime_force_stop_failed");
    }

    private Map<Integer, Entry> snapshot(List<String> paths) throws IOException {
        File[] directories = proc.listFiles();
        if (directories == null) throw new IOException("runtime_process_list_failed");
        Map<Integer, Entry> result = new HashMap<>();
        for (File directory : directories) {
            int pid;
            try { pid = Integer.parseInt(directory.getName()); } catch (NumberFormatException ignored) { continue; }
            if (pid <= 1 || pid == ownPid) continue;
            try {
                String status = read(new File(directory, "status"));
                if (fieldNumber(status, "Uid:") != uid || status.contains("State:\tZ")) continue;
                String args = "";
                try { args = read(new File(directory, "cmdline")); } catch (IOException ignored) {}
                try { args += '\0' + read(new File(directory, "environ")); } catch (IOException ignored) {}
                boolean matches = false;
                for (String path : paths) if (matchesPath(args, path)) { matches = true; break; }
                result.put(pid, new Entry(fieldNumber(status, "PPid:"), matches));
            } catch (IOException ignored) {
                // Processes may exit between directory enumeration and reading their metadata.
            }
        }
        return result;
    }

    static boolean matchesPath(String args, String path) {
        if (path == null || !path.startsWith("/") || path.length() < 2) return false;
        int start = 0;
        while ((start = args.indexOf(path, start)) >= 0) {
            int end = start + path.length();
            boolean left = start == 0 || "\0= :\"'".indexOf(args.charAt(start - 1)) >= 0;
            boolean right = end == args.length() || "\0/ :\"'".indexOf(args.charAt(end)) >= 0;
            if (left && right) return true;
            start = end;
        }
        return false;
    }

    private static Set<Integer> targets(Map<Integer, Entry> entries) {
        Set<Integer> result = new HashSet<>();
        for (Map.Entry<Integer, Entry> entry : entries.entrySet()) if (entry.getValue().matched) result.add(entry.getKey());
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<Integer, Entry> entry : entries.entrySet())
                if (result.contains(entry.getValue().parent)) changed |= result.add(entry.getKey());
        } while (changed);
        return result;
    }

    private static int fieldNumber(String status, String key) throws IOException {
        for (String line : status.split("\n")) if (line.startsWith(key)) {
            try { return Integer.parseInt(line.substring(key.length()).trim().split("\\s+")[0]); }
            catch (NumberFormatException error) { throw new IOException("invalid_process_status", error); }
        }
        throw new IOException("invalid_process_status");
    }

    private static String read(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1 && output.size() < 262144) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class Entry {
        final int parent;
        boolean matched;
        Entry(int parent, boolean matched) { this.parent = parent; this.matched = matched; }
    }
}
