package com.termux.x11;

/**
 * Xorg-process callbacks used by the Vortek Vulkan RPC server.
 *
 * <p>The native methods synchronously marshal window access to Xorg's main
 * thread. {@link #getWindowHardwareBuffer(int, boolean)} transfers one
 * {@code AHardwareBuffer} reference to the caller, which must release it with
 * {@link #releaseWindowHardwareBuffer(long)} after destroying its Vulkan import.</p>
 */
final class VortekServerCallbacks {
    native int getWindowWidth(int windowId);
    native int getWindowHeight(int windowId);
    native long getWindowHardwareBuffer(int windowId, boolean bgra8888);
    native void releaseWindowHardwareBuffer(long buffer);
    native void updateWindowContent(int windowId, long buffer);
}
