package com.termux.ui.wine.domain;

/** Exact catalog identities shared by selection, bindings and component maintenance. */
public final class ProotBox64Versions {
    public static final String DEFAULT_ID = "rootfs_box64_0.4.4";

    private ProotBox64Versions() { }

    public static boolean isBuiltIn(String id) {
        return DEFAULT_ID.equals(id) || "rootfs_box64_0.2.8".equals(id) || "rootfs_box64_0.3.8".equals(id);
    }

    public static String selectedId(String value) {
        return value == null || value.isEmpty() ? DEFAULT_ID : value;
    }
}
