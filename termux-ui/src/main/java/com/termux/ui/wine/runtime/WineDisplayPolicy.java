package com.termux.ui.wine.runtime;

import com.termux.ui.wine.domain.RuntimeProfile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persist the selection; freeze only numeric geometry into a launch. */
public final class WineDisplayPolicy {
    public static final String NATIVE = "native";
    public static final String DEFAULT = "1280x720";

    private WineDisplayPolicy() { }

    public static String[] resolutions(String current) {
        List<String> values = new ArrayList<>(Arrays.asList(NATIVE, "960x540", DEFAULT,
            "1600x900", "1920x1080", "2560x1440"));
        if (!values.contains(current)) values.add(current);
        return values.toArray(new String[0]);
    }

    public static String requireNumeric(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,4}x[1-9][0-9]{0,4}"))
            throw new IllegalArgumentException("invalid_wine_resolution");
        String[] parts = value.split("x");
        if (Integer.parseInt(parts[0]) > Short.MAX_VALUE || Integer.parseInt(parts[1]) > Short.MAX_VALUE)
            throw new IllegalArgumentException("invalid_wine_resolution");
        return value;
    }

    public static String resolve(String selection, int width, int height) {
        if (NATIVE.equals(selection) && (width <= 0 || height <= 0))
            throw new IllegalArgumentException("window_size_required");
        return requireNumeric(NATIVE.equals(selection) ? width + "x" + height : selection);
    }

    public static String preparationResolution(String selection) {
        return NATIVE.equals(selection) ? DEFAULT : requireNumeric(selection);
    }

    public static RuntimeProfile forLaunch(RuntimeProfile profile, String output) {
        requireNumeric(output);
        Map<String, String> environment = new LinkedHashMap<>(profile.getEnvironment());
        // Reserved overrides must agree with the geometry shown by the X11 host.
        for (String key : new String[]{"RESOLUTION", "TERMUX_BOX_RESOLUTION"}) {
            if (environment.containsKey(key)) environment.put(key, output);
        }
        if (environment.containsKey("X_SERVER_SCREEN")) environment.put("X_SERVER_SCREEN", output + "x24");
        return new RuntimeProfile(profile.getId(), profile.getWinePackage(), profile.getGraphicsDriver(),
            profile.getDxWrapper(), profile.getAudioDriver(), output, profile.getBox64Preset(), environment,
            profile.getInputProfileId(), profile.getLaunchExecutionMode(), profile.getComponentVersions(),
            profile.getRuntimeBackendType(), profile.getRootfsPackage(), profile.getContainerId(),
            profile.isXinputEnabled(), profile.isDinputEnabled());
    }
}
