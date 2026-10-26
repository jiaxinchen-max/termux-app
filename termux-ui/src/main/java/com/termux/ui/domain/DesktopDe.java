package com.termux.ui.domain;

/**
 * The desktop environment a managed desktop runs.
 *
 * <p>Only {@link #isCreatable()} entries can be created today; the others are shown greyed out as
 * "coming soon" so the option list documents the roadmap (hyprland, Ubuntu GNOME).
 */
public enum DesktopDe {
    XFCE4("xfce4", true),
    KWIN_PLASMA("kwin_plasma", true),
    HYPRLAND("hyprland", false),
    UBUNTU_GNOME("ubuntu_gnome", false);

    private final String storageValue;
    private final boolean creatable;

    DesktopDe(String storageValue, boolean creatable) {
        this.storageValue = storageValue;
        this.creatable = creatable;
    }

    public String getStorageValue() { return storageValue; }

    /** Whether the create screens offer this environment. */
    public boolean isCreatable() { return creatable; }

    public static DesktopDe fromStorageValue(String value) {
        for (DesktopDe de : values()) {
            if (de.storageValue.equals(value)) return de;
        }
        throw new IllegalArgumentException("unknown_desktop_de:" + value);
    }

    /** Schema-1 desktops only ever ran Plasma. */
    public static DesktopDe legacyDefault() { return KWIN_PLASMA; }
}
