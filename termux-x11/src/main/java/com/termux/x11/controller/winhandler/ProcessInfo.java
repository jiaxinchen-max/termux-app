package com.termux.x11.controller.winhandler;


import com.termux.x11.controller.core.StringUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ProcessInfo {
    public final int pid;
    public final String name;
    public final long memoryUsage;
    public final int affinityMask;
    public final boolean wow64Process;

    private static final int MAX_PROCESS_INFO_COUNT = 50;

    public ProcessInfo(int pid, String name, long memoryUsage, int affinityMask, boolean wow64Process) {
        this.pid = pid;
        this.name = name;
        this.memoryUsage = memoryUsage;
        this.affinityMask = affinityMask;
        this.wow64Process = wow64Process;
    }

    public String getFormattedMemoryUsage() {
        return StringUtils.formatBytes(memoryUsage);
    }

    public String getCPUList() {
        int numProcessors = Runtime.getRuntime().availableProcessors();
        ArrayList<String> cpuList = new ArrayList<>();
        for (byte i = 0; i < numProcessors; i++) {
            if ((affinityMask & (1 << i)) != 0) cpuList.add(String.valueOf(i));
        }
        return String.join(",", cpuList.toArray(new String[0]));
    }

    /** Collect process information from /proc. Returns top processes by memory usage. */
    @androidx.annotation.Nullable
    public static List<ProcessInfo> collectFromProc() {
        File[] processDirs = new File("/proc").listFiles();
        if (processDirs == null) {
            return null;
        }
        int uid = android.os.Process.myUid();
        ArrayList<ProcessInfo> processInfoList = new ArrayList<>();
        for (File processDir : processDirs) {
            if (!isPidDirectory(processDir))
                continue;
            ProcessInfo info = readProcStatus(processDir, uid);
            if (info != null)
                processInfoList.add(info);
        }
        processInfoList.sort((left, right) -> Long.compare(right.memoryUsage, left.memoryUsage));
        if (processInfoList.size() > MAX_PROCESS_INFO_COUNT) {
            return new ArrayList<>(processInfoList.subList(0, MAX_PROCESS_INFO_COUNT));
        }
        return processInfoList.isEmpty() ? null : processInfoList;
    }

    private static boolean isPidDirectory(File file) {
        String name = file.getName();
        if (name.isEmpty()) return false;
        for (int i = 0; i < name.length(); i++) {
            if (!Character.isDigit(name.charAt(i))) return false;
        }
        return file.isDirectory();
    }

    private static ProcessInfo readProcStatus(File processDir, int expectedUid) {
        int pid;
        try {
            pid = Integer.parseInt(processDir.getName());
        } catch (NumberFormatException e) {
            return null;
        }
        String name = null;
        char state = 0;
        int uid = -1;
        long memoryUsage = 0;
        int affinityMask = defaultAffinityMask();
        File statusFile = new File(processDir, "status");
        try (BufferedReader reader = new BufferedReader(new FileReader(statusFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Name:"))
                    name = line.substring("Name:".length()).trim();
                else if (line.startsWith("State:"))
                    state = parseProcState(line.substring("State:".length()));
                else if (line.startsWith("Uid:"))
                    uid = parseFirstInt(line.substring("Uid:".length()), -1);
                else if (line.startsWith("VmRSS:"))
                    memoryUsage = parseFirstLong(line.substring("VmRSS:".length()), 0) * 1024L;
                else if (line.startsWith("Cpus_allowed:"))
                    affinityMask = parseCpuMask(line.substring("Cpus_allowed:".length()));
            }
        } catch (IOException e) {
            return null;
        }
        if (uid != expectedUid || name == null || name.isEmpty() || isDeadProcState(state))
            return null;
        return new ProcessInfo(pid, name, memoryUsage, affinityMask, false);
    }

    private static char parseProcState(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? 0 : trimmed.charAt(0);
    }

    private static boolean isDeadProcState(char state) {
        return state == 'Z' || state == 'X' || state == 'x';
    }

    private static int parseFirstInt(String value, int fallback) {
        long result = parseFirstLong(value, fallback);
        return result > Integer.MAX_VALUE ? fallback : (int) result;
    }

    private static long parseFirstLong(String value, long fallback) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return fallback;
        String[] parts = trimmed.split("\\s+");
        try {
            return Long.parseLong(parts[0]);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseCpuMask(String value) {
        String mask = value.replace(",", "").trim();
        if (mask.isEmpty()) return defaultAffinityMask();
        if (mask.length() > 8) mask = mask.substring(mask.length() - 8);
        try {
            int parsed = (int) Long.parseLong(mask, 16);
            return parsed == 0 ? defaultAffinityMask() : parsed;
        } catch (NumberFormatException e) {
            return defaultAffinityMask();
        }
    }

    private static int defaultAffinityMask() {
        int processors = Math.min(Runtime.getRuntime().availableProcessors(), 30);
        return (1 << processors) - 1;
    }
}
