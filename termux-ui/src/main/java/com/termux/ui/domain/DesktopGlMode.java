package com.termux.ui.domain;

/** KWin rendering path, passed to the distribution as {@code KWIN_ANDROID_GL_MODE}. */
public enum DesktopGlMode {
    /** Legacy value retained for loading old desktops; launching requires an explicit mode. */
    AUTO("auto"),
    /** Android system EGL/GLES with native AHardwareBuffer import. */
    SYSTEM("system"),
    /** Mesa Zink over the Turnip Vulkan driver (Adreno only). */
    ZINK("zink"),
    /** Software rendering; never selected automatically. */
    LLVMPIPE("llvmpipe");

    private final String storageValue;

    DesktopGlMode(String storageValue) {
        this.storageValue = storageValue;
    }

    public String getStorageValue() { return storageValue; }

    public static DesktopGlMode fromStorageValue(String value) {
        for (DesktopGlMode mode : values()) {
            if (mode.storageValue.equals(value)) return mode;
        }
        throw new IllegalArgumentException("unknown_desktop_gl_mode:" + value);
    }
}
