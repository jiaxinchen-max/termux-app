package com.termux.app.terminal;

import static com.termux.shared.termux.TermuxConstants.TERMUX_HOME_DIR_PATH;

import java.io.File;

final class ToolboxConfigFiles {
    private static final File CONFIG_DIR = new File(TERMUX_HOME_DIR_PATH, ".termux/config");

    private ToolboxConfigFiles() {}

    static File file(String name) {
        CONFIG_DIR.mkdirs();
        return new File(CONFIG_DIR, name);
    }

    static File existingFile(String name) {
        return file(name);
    }
}
