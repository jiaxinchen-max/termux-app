package com.termux.ui.domain;

/**
 * One managed KWin desktop distribution: where it runs (native or proot), which rendering path it
 * uses and the output geometry handed to KWin. Immutable; change a field with the {@code with*}
 * copies.
 */
public final class DesktopDistro {

    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_PROOT_DISTRO = "debian";
    public static final String DEFAULT_RESOLUTION = "1280x720";
    public static final int DEFAULT_REFRESH_RATE = 30;
    public static final int MAX_REFRESH_RATE = 120;

    private final String id;
    private final String name;
    private final DesktopRunMode runMode;
    private final String prootDistro;
    private final DesktopGlMode glMode;
    private final String resolution;
    private final int refreshRate;

    public DesktopDistro(String id, String name, DesktopRunMode runMode, String prootDistro,
                         DesktopGlMode glMode, String resolution, int refreshRate) {
        this.id = requireId(id);
        this.name = DomainValidation.requireText(name, "desktopName");
        if (runMode == null) throw new IllegalArgumentException("desktopRunModeRequired");
        if (glMode == null) throw new IllegalArgumentException("desktopGlModeRequired");
        this.runMode = runMode;
        this.glMode = glMode;
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

    public String getId() { return id; }
    public String getName() { return name; }
    public DesktopRunMode getRunMode() { return runMode; }
    /** The proot-distro alias for {@link DesktopRunMode#PROOT}; empty for native. */
    public String getProotDistro() { return prootDistro; }
    public DesktopGlMode getGlMode() { return glMode; }
    /** {@code WIDTHxHEIGHT}, also the X11 display resolution of the session view. */
    public String getResolution() { return resolution; }
    public int getRefreshRate() { return refreshRate; }

    public int getWidth() { return Integer.parseInt(resolution.substring(0, resolution.indexOf('x'))); }
    public int getHeight() { return Integer.parseInt(resolution.substring(resolution.indexOf('x') + 1)); }

    public DesktopDistro withName(String newName) {
        return new DesktopDistro(id, newName, runMode, prootDistro, glMode, resolution, refreshRate);
    }

    public DesktopDistro withGlMode(DesktopGlMode newGlMode) {
        return new DesktopDistro(id, name, runMode, prootDistro, newGlMode, resolution, refreshRate);
    }

    public DesktopDistro withOutput(String newResolution, int newRefreshRate) {
        return new DesktopDistro(id, name, runMode, prootDistro, glMode, newResolution,
            newRefreshRate);
    }

    public static boolean isValidId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,128}");
    }

    private static String requireId(String value) {
        if (!isValidId(value)) throw new IllegalArgumentException("invalidDesktopId");
        return value;
    }

    private static String requireResolution(String value) {
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
