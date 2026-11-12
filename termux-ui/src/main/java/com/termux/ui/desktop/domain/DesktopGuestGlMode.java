package com.termux.ui.desktop.domain;

/** Container renderer policy; Android System EGL is never a glibc container mode. */
public enum DesktopGuestGlMode {
    NONE("none"), DEFAULT("default"), ZINK("zink"), LLVMPIPE("llvmpipe"), UNRESOLVED("unresolved");
    private final String value;
    DesktopGuestGlMode(String value) { this.value = value; }
    public String getStorageValue() { return value; }
    public static DesktopGuestGlMode fromStorageValue(String value) {
        for (DesktopGuestGlMode mode : values()) if (mode.value.equals(value)) return mode;
        throw new IllegalArgumentException("unknown_desktop_guest_gl_mode:" + value);
    }
}
