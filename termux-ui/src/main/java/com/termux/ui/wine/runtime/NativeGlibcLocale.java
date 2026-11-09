package com.termux.ui.wine.runtime;

import java.io.File;

/** Shared host locale data published by the glibc installer, independent of game prefixes. */
public final class NativeGlibcLocale {
    private NativeGlibcLocale() { }

    public static boolean isReady(File glibcDirectory) {
        return new File(glibcDirectory, "termux-box/run/games-zh-cn-locale-v1").isFile()
            && nonEmpty(new File(glibcDirectory, "lib/locale/zh_CN.utf8/LC_CTYPE"))
            && nonEmpty(new File(glibcDirectory, "lib/locale/zh_CN.gbk/LC_CTYPE"));
    }

    private static boolean nonEmpty(File file) {
        return file.isFile() && file.length() > 0;
    }
}
