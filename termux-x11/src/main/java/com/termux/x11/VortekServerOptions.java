package com.termux.x11;

import androidx.annotation.Keep;

/**
 * ABI consumed by the Vortek native RPC server.  These defaults deliberately
 * expose the Android Vulkan device without a Winlator-specific driver layer.
 */
@Keep
public final class VortekServerOptions {
    @Keep public int vkMaxVersion = (1 << 22) | (3 << 12) | 128;
    @Keep public short maxDeviceMemory = 0;
    @Keep public short imageCacheSize = 256;
    @Keep public byte resourceMemoryType = 0;
    @Keep public String[] exposedDeviceExtensions;
}
