package com.termux.app.activities.termuxbox;

import androidx.annotation.NonNull;

public final class TermuxBoxPackageSpec {
    public final String name;
    public final int version;
    public final boolean wine;
    public final String category;
    public final String url;
    public final long size;
    public final String sha256;

    public TermuxBoxPackageSpec(@NonNull String name, int version, boolean wine) {
        this(name, version, wine ? "wine" : "runtime", "", 0, "");
    }

    public TermuxBoxPackageSpec(@NonNull String name, int version, @NonNull String category,
                                @NonNull String url, long size, @NonNull String sha256) {
        this.name = name;
        this.version = version;
        this.category = category;
        this.wine = "wine".equals(category);
        this.url = url;
        this.size = size;
        this.sha256 = sha256;
    }
}
