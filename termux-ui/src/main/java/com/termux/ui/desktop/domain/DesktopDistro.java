package com.termux.ui.desktop.domain;

import com.termux.ui.domain.DomainValidation;

/**
 * One managed KWin desktop distribution: where it runs (native or proot), which rendering path it
 * uses and the output geometry handed to KWin. Immutable; change a field with the {@code with*}
 * copies.
 */
public final class DesktopDistro {

    public static final int SCHEMA_VERSION = 3;
    public static final String DEFAULT_PROOT_DISTRO = "debian";
    public static final String DEFAULT_RESOLUTION = "1280x720";
    public static final String NATIVE_RESOLUTION = "native";
    public static final int DEFAULT_REFRESH_RATE = 30;
    public static final int MAX_REFRESH_RATE = 120;

    public static final int HYPRLAND_SCHEMA_VERSION = 4;

    private final String hyprlandRuntimeId;
    private final String hyprlandThemeId;
    private final String id;
    private final String name;
    private final DesktopRunMode runMode;
    private final DesktopDe de;
    private final String prootDistro;
    private final DesktopGlMode hostGlMode;
    private final DesktopGuestGlMode guestGlMode;
    private final String resolution;
    private final int refreshRate;
    private final DesktopUsbStorageMode usbStorage;
    private final long createdAt;

    public DesktopDistro(String id, String name, DesktopRunMode runMode, String prootDistro,
                         DesktopDe de, DesktopGlMode hostGlMode, DesktopGuestGlMode guestGlMode,
                         String resolution, int refreshRate, DesktopUsbStorageMode usbStorage) {
        this(id, name, runMode, prootDistro, de, hostGlMode, guestGlMode, resolution, refreshRate,
            usbStorage, de == DesktopDe.HYPRLAND ? HyprlandComponents.RUNTIME_ID : "",
            de == DesktopDe.HYPRLAND ? HyprlandComponents.DEFAULT_THEME : "");
    }

    public DesktopDistro(String id, String name, DesktopRunMode runMode, String prootDistro,
                         DesktopDe de, DesktopGlMode hostGlMode, DesktopGuestGlMode guestGlMode,
                         String resolution, int refreshRate, DesktopUsbStorageMode usbStorage,
                         String runtimeId, String themeId) {
        this(id, name, runMode, prootDistro, de, hostGlMode, guestGlMode, resolution, refreshRate,
            usbStorage, runtimeId, themeId, System.currentTimeMillis());
    }

    public DesktopDistro(String id, String name, DesktopRunMode runMode, String prootDistro,
                         DesktopDe de, DesktopGlMode hostGlMode, DesktopGuestGlMode guestGlMode,
                         String resolution, int refreshRate, DesktopUsbStorageMode usbStorage,
                         String runtimeId, String themeId, long createdAt) {
        if (createdAt < 0) throw new IllegalArgumentException("invalid_creation_time");
        this.createdAt = createdAt;
        if (de == DesktopDe.HYPRLAND) HyprlandComponents.requireSupported(runtimeId, themeId);
        else if (!"".equals(runtimeId) || !"".equals(themeId))
            throw new IllegalArgumentException("hyprland_components_require_hyprland");
        this.hyprlandRuntimeId = runtimeId;
        this.hyprlandThemeId = themeId;
        if (usbStorage == null) throw new IllegalArgumentException("desktopUsbStorageRequired");
        this.usbStorage = usbStorage;
        if (de == null) throw new IllegalArgumentException("desktopDeRequired");
        this.de = de;
        this.id = requireId(id);
        this.name = DomainValidation.requireText(name, "desktopName");
        if (runMode == null) throw new IllegalArgumentException("desktopRunModeRequired");
        if (hostGlMode == null || guestGlMode == null) throw new IllegalArgumentException("desktopGlModeRequired");
        this.runMode = runMode;
        this.hostGlMode = hostGlMode;
        this.guestGlMode = guestGlMode;
        if ((runMode == DesktopRunMode.NATIVE) != (guestGlMode == DesktopGuestGlMode.NONE))
            throw new IllegalArgumentException("desktopGuestModeRunModeMismatch");
        String distro = DomainValidation.optionalText(prootDistro);
        if (runMode == DesktopRunMode.PROOT) {
            if (!distro.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
                throw new IllegalArgumentException("invalidDesktopProotDistro");
            }
        } else if (!distro.isEmpty()) {
            throw new IllegalArgumentException("desktopProotDistroUnsupported");
        }
        this.prootDistro = distro;
        this.resolution = requireResolution(resolution);
        if (refreshRate < 1 || refreshRate > MAX_REFRESH_RATE) {
            throw new IllegalArgumentException("invalidDesktopRefreshRate");
        }
        this.refreshRate = refreshRate;
    }

    public String getHyprlandRuntimeId() { return hyprlandRuntimeId; }
    public String getHyprlandThemeId() { return hyprlandThemeId; }
    public int getSchemaVersion() { return de == DesktopDe.HYPRLAND ? HYPRLAND_SCHEMA_VERSION : SCHEMA_VERSION; }
    public DesktopDistro withHyprlandComponents(String runtime, String theme) {
        return new DesktopDistro(id, name, runMode, prootDistro, de, hostGlMode, guestGlMode,
            resolution, refreshRate, usbStorage, runtime, theme, createdAt);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public long getCreatedAt() { return createdAt; }
    public DesktopRunMode getRunMode() { return runMode; }
    /** The proot-distro alias of this desktop's own container instance; empty for native. */
    public String getProotDistro() { return prootDistro; }
    public DesktopDe getDe() { return de; }
    public DesktopGlMode getHostGlMode() { return hostGlMode; }
    public DesktopGuestGlMode getGuestGlMode() { return guestGlMode; }
    /** {@code WIDTHxHEIGHT}, or {@code native} resolved when the session starts. */
    public String getResolution() { return resolution; }
    public boolean isNativeResolution() { return NATIVE_RESOLUTION.equals(resolution); }
    public int getRefreshRate() { return refreshRate; }
    public DesktopUsbStorageMode getUsbStorage() { return usbStorage; }

    public int getWidth() { requireFixedResolution(); return Integer.parseInt(resolution.substring(0, resolution.indexOf('x'))); }
    public int getHeight() { requireFixedResolution(); return Integer.parseInt(resolution.substring(resolution.indexOf('x') + 1)); }

    private void requireFixedResolution() {
        if (isNativeResolution()) throw new IllegalStateException("desktop_native_resolution_requires_window_size");
    }

    public DesktopDistro withName(String newName) {
        return new DesktopDistro(id, newName, runMode, prootDistro, de, hostGlMode, guestGlMode, resolution,
            refreshRate, usbStorage, hyprlandRuntimeId, hyprlandThemeId, createdAt);
    }

    public DesktopDistro withRenderers(DesktopGlMode host, DesktopGuestGlMode guest) {
        return new DesktopDistro(id, name, runMode, prootDistro, de, host, guest, resolution, refreshRate, usbStorage, hyprlandRuntimeId, hyprlandThemeId, createdAt);
    }

    public DesktopDistro withOutput(String newResolution, int newRefreshRate) {
        return new DesktopDistro(id, name, runMode, prootDistro, de, hostGlMode, guestGlMode, newResolution,
            newRefreshRate, usbStorage, hyprlandRuntimeId, hyprlandThemeId, createdAt);
    }

    public DesktopDistro withUsbStorage(DesktopUsbStorageMode mode) {
        return new DesktopDistro(id, name, runMode, prootDistro, de, hostGlMode, guestGlMode, resolution,
            refreshRate, mode, hyprlandRuntimeId, hyprlandThemeId, createdAt);
    }

    public static boolean isValidId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }

    private static String requireId(String value) {
        if (!isValidId(value)) throw new IllegalArgumentException("invalidDesktopId");
        return value;
    }

    private static String requireResolution(String value) {
        if (NATIVE_RESOLUTION.equals(value)) return value;
        if (value == null || !value.matches("[1-9][0-9]{2,4}x[1-9][0-9]{2,4}")) {
            throw new IllegalArgumentException("invalidDesktopResolution");
        }
        String[] parts = value.split("x");
        if (Integer.parseInt(parts[0]) > Short.MAX_VALUE || Integer.parseInt(parts[1]) > Short.MAX_VALUE) {
            throw new IllegalArgumentException("invalidDesktopResolution");
        }
        return value;
    }
}
