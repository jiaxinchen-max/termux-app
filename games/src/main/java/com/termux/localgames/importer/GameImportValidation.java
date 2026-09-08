package com.termux.localgames.importer;

/** Path validation shared by import UI and persistence. */
public final class GameImportValidation {

    private GameImportValidation() {}

    public static boolean isSafeRelativeDirectory(String value) {
        return isSafeRelativePath(value, true);
    }

    public static boolean isSafeRelativeFile(String value) {
        return isSafeRelativePath(value, false);
    }

    public static void requireSafeGamePaths(String executable, String workingDirectory) {
        if (!isSafeRelativeFile(executable)) {
            throw new IllegalArgumentException("invalid_game_executable");
        }
        if (!isSafeRelativeDirectory(workingDirectory)) {
            throw new IllegalArgumentException("invalid_game_working_directory");
        }
    }

    private static boolean isSafeRelativePath(String value, boolean allowRoot) {
        if (value == null) return false;
        if (allowRoot && value.equals(".")) return true;
        if (value.isEmpty() || value.startsWith("/") || value.startsWith("\\") ||
            value.indexOf('\\') >= 0 || value.indexOf('\0') >= 0 || value.contains(":")) {
            return false;
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) return false;
        }
        return true;
    }
}
