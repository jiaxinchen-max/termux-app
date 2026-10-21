package com.termux.x11.controller.winhandler;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the executable file behind a Wine process: its Windows path comes from the process command
 * line, and the drive letter is mapped to a unix directory through the prefix's
 * {@code dosdevices} links. Pure file/string logic, no Android dependency.
 */
public final class WineProcessPaths {
    private static final Pattern WINDOWS_EXE = Pattern.compile("[A-Za-z]:\\\\[^\\u0000\"]*?\\.exe", Pattern.CASE_INSENSITIVE);

    private WineProcessPaths() { }

    /**
     * Candidate Windows paths of the executable of the process named {@code processName}, best first.
     * Linux truncates process names to 15 characters, so a path matches when its file name starts
     * with the (case-insensitively compared) name. Names ending in {@code .exe} also fall back to
     * {@code system32} and the Windows directory (builtin programs). Empty for anything else, such
     * as Android processes.
     */
    public static List<String> candidateWindowsPaths(@Nullable String cmdline, @Nullable String processName) {
        List<String> result = new ArrayList<>();
        if (processName == null || processName.isEmpty()) return result;
        String wanted = processName.toLowerCase(Locale.ROOT);
        if (cmdline != null) {
            Matcher matcher = WINDOWS_EXE.matcher(cmdline);
            while (matcher.find()) {
                String path = matcher.group();
                String fileName = path.substring(path.lastIndexOf('\\') + 1).toLowerCase(Locale.ROOT);
                if (fileName.startsWith(wanted) && !result.contains(path)) result.add(path);
            }
        }
        if (wanted.endsWith(".exe")) {
            result.add("C:\\windows\\system32\\" + processName);
            result.add("C:\\windows\\" + processName);
        }
        return result;
    }

    /** Value of {@code key} in a NUL-separated environment block, or null. */
    @Nullable
    public static String environmentValue(@Nullable String environ, String key) {
        if (environ == null) return null;
        String prefix = key + "=";
        for (String entry : environ.split("\u0000")) {
            if (entry.startsWith(prefix)) return entry.substring(prefix.length());
        }
        return null;
    }

    /**
     * The existing unix file for {@code windowsPath} inside the Wine prefix {@code prefix}, or null.
     * The drive comes from {@code dosdevices/<letter>:} (drive C also from {@code drive_c}), the rest
     * is matched case-insensitively like Wine does; parent-directory segments are rejected.
     */
    @Nullable
    public static File toUnixFile(File prefix, String windowsPath) {
        if (windowsPath.length() < 4 || windowsPath.charAt(1) != ':' || windowsPath.charAt(2) != '\\') return null;
        char drive = Character.toLowerCase(windowsPath.charAt(0));
        File current = new File(prefix, "dosdevices/" + drive + ":");
        if (!current.isDirectory() && drive == 'c') current = new File(prefix, "drive_c");
        if (!current.isDirectory()) return null;
        String[] segments = windowsPath.substring(3).split("\\\\");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return null;
            File next = child(current, segment);
            if (next == null) return null;
            if (i < segments.length - 1 && !next.isDirectory()) return null;
            current = next;
        }
        return current.isFile() ? current : null;
    }

    @Nullable
    private static File child(File directory, String name) {
        File exact = new File(directory, name);
        if (exact.exists()) return exact;
        String[] names = directory.list();
        if (names == null) return null;
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) return new File(directory, candidate);
        }
        return null;
    }
}
