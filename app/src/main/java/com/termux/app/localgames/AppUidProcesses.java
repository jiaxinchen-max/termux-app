package com.termux.app.localgames;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads identity again before signalling so a recycled PID cannot select another process. */
final class AppUidProcesses {
    static final class Entry {
        final int pid;
        final String startedAt;
        Entry(int pid, String startedAt) { this.pid = pid; this.startedAt = startedAt; }
    }

    static List<Entry> scan(File proc, int uid, int excludedPid) throws IOException {
        File[] directories = proc.listFiles();
        if (directories == null) throw new IOException("Cannot enumerate " + proc);
        List<Entry> entries = new ArrayList<>();
        for (File directory : directories) {
            int pid;
            try { pid = Integer.parseInt(directory.getName()); }
            catch (NumberFormatException ignored) { continue; }
            if (pid <= 1 || pid == excludedPid) continue;
            Entry entry = read(proc, pid, uid);
            if (entry != null) entries.add(entry);
        }
        return entries;
    }

    static boolean matches(File proc, Entry expected, int uid) {
        Entry current = read(proc, expected.pid, uid);
        return current != null && expected.startedAt.equals(current.startedAt);
    }

    private static Entry read(File proc, int pid, int uid) {
        File directory = new File(proc, Integer.toString(pid));
        try (BufferedReader status = new BufferedReader(new FileReader(new File(directory, "status")))) {
            boolean owned = false;
            String line;
            while ((line = status.readLine()) != null) {
                if (!line.startsWith("Uid:")) continue;
                String[] ids = line.substring(4).trim().split("\\s+");
                if (ids.length != 4) return null;
                for (String id : ids) if (Integer.parseInt(id) != uid) return null;
                owned = true;
                break;
            }
            if (!owned) return null;
            try (BufferedReader stat = new BufferedReader(new FileReader(new File(directory, "stat")))) {
                String value = stat.readLine();
                if (value == null || value.lastIndexOf(')') < 0) return null;
                String[] fields = value.substring(value.lastIndexOf(')') + 1).trim().split("\\s+");
                if (fields.length < 20 || "Z".equals(fields[0]) || "X".equals(fields[0])) return null;
                return new Entry(pid, fields[19]);
            }
        } catch (IOException | NumberFormatException ignored) {
            // Other UIDs are hidden by procfs; exiting processes may disappear during the read.
            return null;
        }
    }
}
