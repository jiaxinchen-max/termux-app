package com.termux.x11;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;

/**
 * Test-only owner for probing the Bionic-side Vulkan broker.
 *
 * <p>Production sessions start the broker inside the Xorg process from
 * {@code CmdEntryPoint}; starting it from the Android UI process cannot access Xorg windows.
 * The broker deliberately exposes a Unix-domain socket rather than Android Vulkan objects.
 * GLIBC clients will use an ICD/proxy and never receive an {@code AHardwareBuffer} pointer.</p>
 */
public final class VulkanBroker implements AutoCloseable {
    static {
        System.loadLibrary("Xlorie");
    }

    @Nullable private File socketFile;

    /** Starts a broker at a path accessible to the Termux process running this session. */
    public synchronized boolean start(@NonNull File socket) throws IOException {
        File parent = socket.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
            throw new IOException("Cannot create Vulkan broker directory");
        if (!nativeStart(socket.getAbsolutePath())) return false;
        socketFile = socket;
        return true;
    }

    @Nullable public synchronized String getSocketPath() {
        return socketFile == null ? null : socketFile.getAbsolutePath();
    }

    public synchronized boolean isRunning() {
        return nativeIsRunning();
    }

    @Override public synchronized void close() {
        nativeStop();
        socketFile = null;
    }

    private static native boolean nativeStart(String socketPath);
    private static native void nativeStop();
    private static native boolean nativeIsRunning();
}
