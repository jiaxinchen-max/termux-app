package com.termux.ui.wine.runtime;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Host packages must be usable before preparing any Native component or prefix. */
public final class NativeBaseRuntimePrerequisite {
    private NativeBaseRuntimePrerequisite() { }

    @FunctionalInterface
    public interface Installer {
        void install() throws IOException;
    }

    public static void ensure(BooleanSupplier available, Installer installer) throws IOException {
        if (available.getAsBoolean()) return;
        installer.install();
        if (!available.getAsBoolean()) {
            throw new IOException("glibc_runtime_verification_failed");
        }
    }
}
