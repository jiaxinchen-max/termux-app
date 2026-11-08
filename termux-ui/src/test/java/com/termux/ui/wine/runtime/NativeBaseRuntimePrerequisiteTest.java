package com.termux.ui.wine.runtime;

import org.junit.Test;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class NativeBaseRuntimePrerequisiteTest {
    @Test public void usableHostSkipsPackageInstallation() throws Exception {
        NativeBaseRuntimePrerequisite.ensure(() -> true,
            () -> fail("Existing runtime must be reused"));
    }

    @Test public void freshHostInstallsOnceAndSubsequentPrepareReusesIt() throws Exception {
        AtomicBoolean available = new AtomicBoolean();
        AtomicInteger installs = new AtomicInteger();
        NativeBaseRuntimePrerequisite.Installer installer = () -> {
            installs.incrementAndGet();
            available.set(true);
        };
        NativeBaseRuntimePrerequisite.ensure(available::get, installer);
        NativeBaseRuntimePrerequisite.ensure(available::get, installer);
        assertEquals(1, installs.get());
    }

    @Test public void failedInstallStopsCallerWithoutRetrying() throws Exception {
        AtomicInteger installs = new AtomicInteger();
        IOException failure = new IOException("glibc_package_install_failed");
        try {
            NativeBaseRuntimePrerequisite.ensure(() -> false, () -> {
                installs.incrementAndGet();
                throw failure;
            });
            fail("Component and prefix preparation must not proceed");
        } catch (IOException expected) {
            assertSame(failure, expected);
        }
        assertEquals(1, installs.get());
    }

    @Test public void taskSuccessWithoutUsableHostStopsCaller() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        try {
            NativeBaseRuntimePrerequisite.ensure(() -> { probes.incrementAndGet(); return false; }, () -> {});
            fail("A stale successful task cannot make the host ready");
        } catch (IOException expected) {
            assertEquals("glibc_runtime_verification_failed", expected.getMessage());
        }
        assertEquals(2, probes.get());
    }

    @Test public void cancelledInstallStopsCaller() throws Exception {
        try {
            NativeBaseRuntimePrerequisite.ensure(() -> false,
                () -> { throw new IOException("component_prepare_cancelled:termux-glibc"); });
            fail("Cancellation must stop prefix preparation");
        } catch (IOException expected) {
            assertEquals("component_prepare_cancelled:termux-glibc", expected.getMessage());
        }
    }
}
