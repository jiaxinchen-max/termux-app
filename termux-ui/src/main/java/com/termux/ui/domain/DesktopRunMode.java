package com.termux.ui.domain;

/** How a KWin desktop distribution is executed on the device. */
public enum DesktopRunMode {
    /** KWin and Plasma run directly in the Termux prefix. */
    NATIVE("native"),
    /** KWin runs in Termux; the Plasma session runs inside a proot-distro container. */
    PROOT("proot");

    private final String storageValue;

    DesktopRunMode(String storageValue) {
        this.storageValue = storageValue;
    }

    public String getStorageValue() { return storageValue; }

    public static DesktopRunMode fromStorageValue(String value) {
        for (DesktopRunMode mode : values()) {
            if (mode.storageValue.equals(value)) return mode;
        }
        throw new IllegalArgumentException("unknown_desktop_run_mode:" + value);
    }
}
