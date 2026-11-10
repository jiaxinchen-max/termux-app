package com.termux.ui.wine.runtime;

/** Native catalog builds have stable per-version paths, independent of the shared bin directory. */
public final class NativeBox64Versions {
    public static final String DEFAULT_ID = "glibc_box64_0.2.8";

    private NativeBox64Versions() { }

    public static boolean isBuiltIn(String id) {
        return DEFAULT_ID.equals(id) || "glibc_box64_0.3.8".equals(id) || "glibc_box64_0.4.4".equals(id);
    }

    public static String selectedId(String value) {
        return value == null || value.isEmpty() ? DEFAULT_ID : value;
    }

    public static String relativeDirectory(String id) {
        if (!isBuiltIn(id)) throw new IllegalArgumentException("box64_selection_unsupported:" + id);
        return "components/" + id;
    }
}
