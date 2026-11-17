package com.termux.ui.desktop.runtime;

import com.termux.ui.desktop.domain.DesktopDe;
import com.termux.ui.desktop.domain.DesktopDistro;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Display capabilities shared by both run modes, creation, editing and launch. */
public final class DesktopDisplayPolicy {
    private DesktopDisplayPolicy() { }

    public static String[] resolutions(String current) {
        List<String> values = new ArrayList<>(Arrays.asList(DesktopDistro.NATIVE_RESOLUTION,
            "1280x720", "1600x900", "1920x1080", "2560x1440"));
        if (!values.contains(current)) values.add(current);
        return values.toArray(new String[0]);
    }

    public static boolean isRefreshFixed(DesktopDe de) { return de != null && de.isGnomeSession(); }

    public static int effectiveRefreshRate(DesktopDe de, int requested) {
        return isRefreshFixed(de) ? 60 : requested;
    }

    public static int[] refreshRates(DesktopDe de, int current) {
        if (isRefreshFixed(de)) return new int[]{60};
        if (current == 30 || current == 60 || current == 90 || current == 120)
            return new int[]{30, 60, 90, 120};
        return new int[]{30, 60, 90, 120, current};
    }

    /** Static preparation needs numeric geometry; Native is resolved again for actual launch. */
    public static String preparationResolution(DesktopDistro distro) {
        return distro.isNativeResolution() ? DesktopDistro.DEFAULT_RESOLUTION : distro.getResolution();
    }

    public static String resolveResolution(DesktopDistro distro, int windowWidth, int windowHeight) {
        String output = distro.isNativeResolution() ? windowWidth + "x" + windowHeight : distro.getResolution();
        requireOutputResolution(distro, output);
        return output;
    }

    public static void requireOutputResolution(DesktopDistro distro, String output) {
        // Reuse the persisted geometry validator, and apply actual compositor bounds.
        DesktopDistro fixed = distro.withOutput(output, distro.getRefreshRate());
        if (fixed.isNativeResolution()) throw new IllegalArgumentException("desktop_output_must_be_numeric");
        int width = fixed.getWidth(), height = fixed.getHeight();
        if ((distro.getDe().isGnomeSession() || distro.getDe() == DesktopDe.HYPRLAND)
                && (width > 8192 || height > 8192
                    || distro.getDe().isGnomeSession() && (width < 320 || height < 240)))
            throw new IllegalArgumentException("desktop_output_out_of_backend_range");
        if (!distro.isNativeResolution() && !distro.getResolution().equals(output))
            throw new IllegalArgumentException("desktop_fixed_output_mismatch");
    }
}
