package com.termux.ui.wine.runtime;

import java.util.Arrays;
import java.util.List;

/** Exact catalog mapping; never infer support from architecture or a custom package name. */
public final class NativePrebuiltPrefixes {
    public static final List<String> WINES = java.util.Collections.unmodifiableList(Arrays.asList(
        "glibc_wine_8.18-staging", "glibc_wine_9.0-staging", "glibc_wine_9.3-vanilla",
        "glibc_wine_10.0", "glibc_proton_11.0"));

    private NativePrebuiltPrefixes() { }

    public static String componentId(String wine) {
        if (!WINES.contains(wine)) throw new IllegalArgumentException("native_prebuilt_prefix_unsupported");
        return "native_prefix_" + wine.substring("glibc_".length());
    }

    public static boolean isComponentId(String id) {
        for (String wine : WINES) if (componentId(wine).equals(id)) return true;
        return false;
    }
}
