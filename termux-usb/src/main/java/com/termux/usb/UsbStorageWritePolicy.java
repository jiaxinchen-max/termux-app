package com.termux.usb;

/** Fail closed for mounted, read-only mounted, transitional and unknown volume states. */
public final class UsbStorageWritePolicy {
    private UsbStorageWritePolicy() { }
    public static boolean isUnmounted(String state) {
        return "unmounted".equals(state) || "removed".equals(state);
    }
}
