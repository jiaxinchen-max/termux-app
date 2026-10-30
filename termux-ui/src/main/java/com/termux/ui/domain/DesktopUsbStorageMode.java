package com.termux.ui.domain;

/** Per-desktop USB storage policy; read-write access is not supported. */
public enum DesktopUsbStorageMode {
    OFF("off"), READ_ONLY("read-only");
    private final String storageValue;
    DesktopUsbStorageMode(String value) { storageValue=value; }
    public String getStorageValue() { return storageValue; }
    public static DesktopUsbStorageMode fromStorageValue(String value) {
        for (DesktopUsbStorageMode mode:values()) if (mode.storageValue.equals(value)) return mode;
        throw new IllegalArgumentException("unknown_desktop_usb_storage:"+value);
    }
}
