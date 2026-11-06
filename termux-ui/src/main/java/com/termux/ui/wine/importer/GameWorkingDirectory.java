package com.termux.ui.wine.importer;

import java.io.File;
import java.io.IOException;

/** Shows a real directory to the user while persisting a confined, root-relative launch path. */
public final class GameWorkingDirectory {
    private GameWorkingDirectory() {}

    public static String display(File root, String relative) throws IOException {
        if (!GameImportValidation.isSafeRelativeDirectory(relative)) {
            throw new IOException("invalid_game_working_directory");
        }
        File base = root.getCanonicalFile();
        File target = new File(base, relative).getCanonicalFile();
        requireContained(base, target);
        return target.getPath();
    }

    public static String relative(File root, String displayed) throws IOException {
        if (displayed == null || displayed.trim().isEmpty()) {
            throw new IOException("invalid_game_working_directory");
        }
        File base = root.getCanonicalFile();
        File input = new File(displayed.trim());
        if (!input.isAbsolute() && !GameImportValidation.isSafeRelativeDirectory(displayed.trim())) {
            throw new IOException("invalid_game_working_directory");
        }
        File target = (input.isAbsolute() ? input : new File(base, displayed.trim())).getCanonicalFile();
        requireContained(base, target);
        if (!target.isDirectory()) throw new IOException("game_working_directory_missing");
        String relative = target.equals(base) ? "."
            : target.getPath().substring(base.getPath().length() + 1);
        if (!GameImportValidation.isSafeRelativeDirectory(relative)) {
            throw new IOException("invalid_game_working_directory");
        }
        return relative;
    }

    private static void requireContained(File root, File target) throws IOException {
        if (!root.equals(target) && !target.getPath().startsWith(root.getPath() + File.separator)) {
            throw new IOException("game_working_directory_outside_root");
        }
    }
}
