package com.termux.ui.desktop.domain;

import com.termux.ui.domain.DomainValidation;
import java.util.HashSet;
import java.util.Set;

/** Display names only; identity and container paths never depend on this choice. */
public final class DesktopRestoreNames {
    private DesktopRestoreNames() {}
    public static String choose(String original, Iterable<DesktopDistro> inventory) {
        DomainValidation.requireText(original, "desktopName");
        Set<String> used = new HashSet<>();
        for (DesktopDistro desktop : inventory) used.add(desktop.getName());
        if (!used.contains(original)) return original;
        for (long suffix = 1; ; suffix++) {
            String candidate = original + " (" + suffix + ")";
            if (!used.contains(candidate)) return candidate;
        }
    }
}
