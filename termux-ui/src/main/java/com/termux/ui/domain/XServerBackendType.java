package com.termux.ui.domain;

/**
 * Which X server serves a game launch. Both are always present; the selection only decides who
 * starts {@code :0}, so the two implementations can be swapped without touching the games.
 */
public enum XServerBackendType {
    /** The in-process X server in libXlorie.so, launched through the {@code termux-x11} script. */
    LEGACY_IN_PROCESS("legacy", "termux-x11"),
    /** The independent {@code Xlorie} executable from the xserver termuxdc package; it connects
     *  to the display view through termux-render, like KWin does. */
    XLORIE_EXTERNAL("xlorie", "xlorie");

    private final String storageValue;
    private final String hostCapabilityId;

    XServerBackendType(String storageValue, String hostCapabilityId) {
        this.storageValue = storageValue;
        this.hostCapabilityId = hostCapabilityId;
    }

    public String getStorageValue() { return storageValue; }

    /** The host capability a launch needs from the Termux prefix for this X server. */
    public String getHostCapabilityId() { return hostCapabilityId; }

    public static XServerBackendType fromStorageValue(String value) {
        for (XServerBackendType type : values()) {
            if (type.storageValue.equals(value)) return type;
        }
        throw new IllegalArgumentException("unknown_x_server_backend:" + value);
    }
}
