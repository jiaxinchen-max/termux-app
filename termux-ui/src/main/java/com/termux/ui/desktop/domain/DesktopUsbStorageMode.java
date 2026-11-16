package com.termux.ui.desktop.domain;

/** Per-desktop USB storage policy; writes require explicit PRoot selection. */
public enum DesktopUsbStorageMode {
    OFF("off"), READ_ONLY("read-only"), READ_WRITE("read-write");
    private final String storageValue;
    DesktopUsbStorageMode(String value) { storageValue=value; }
    public String getStorageValue() { return storageValue; }
    public static DesktopUsbStorageMode fromStorageValue(String value) {
        for (DesktopUsbStorageMode mode:values()) if (mode.storageValue.equals(value)) return mode;
        throw new IllegalArgumentException("unknown_desktop_usb_storage:"+value);
    }
}
