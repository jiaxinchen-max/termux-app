package com.termux.x11.controller.core;

import android.content.Context;

import java.io.File;

public final class TermuxConfigFiles {
    private TermuxConfigFiles() {}

    public static File buttonIconsDir(Context context) {
        File homeDir = new File(context.getFilesDir(), "home");
        return new File(homeDir, ".termux/buttonIcons");
    }
}
