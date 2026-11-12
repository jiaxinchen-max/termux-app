package com.termux.ui.desktop.domain;

/** Supported desktop kinds; unsupported entries remain readable but are hidden in creation. */
public enum DesktopDe {
    XFCE4("xfce4", true),
    KWIN_PLASMA("kwin_plasma", true),
    GNOME("gnome", true),
    HYPRLAND("hyprland", true),
    UBUNTU_GNOME("ubuntu_gnome", true);

    private final String storageValue;
    private final boolean creatable;

    DesktopDe(String storageValue, boolean creatable) {
        this.storageValue = storageValue;
        this.creatable = creatable;
    }

    public String getStorageValue() { return storageValue; }

    /** Whether the create screens offer this environment. */
    public boolean isCreatable() { return creatable; }

    public boolean supportsNative() { return creatable; }

    public boolean isGnomeSession() { return this == GNOME || this == UBUNTU_GNOME; }

    public static DesktopDe fromStorageValue(String value) {
        for (DesktopDe de : values()) {
            if (de.storageValue.equals(value)) return de;
        }
        throw new IllegalArgumentException("unknown_desktop_de:" + value);
    }

    /** Schema-1 desktops only ever ran Plasma. */
    public static DesktopDe legacyDefault() { return KWIN_PLASMA; }
}
