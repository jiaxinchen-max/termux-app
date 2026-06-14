package com.termux.app.activities.termuxbox;

import androidx.annotation.NonNull;

public final class TermuxBoxPackageSpec {
    public final String name;
    public final int version;
    public final boolean wine;

    public TermuxBoxPackageSpec(@NonNull String name, int version, boolean wine) {
        this.name = name;
        this.version = version;
        this.wine = wine;
    }
}
