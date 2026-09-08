package com.termux.localgames.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic game-level baselines; CUSTOM intentionally preserves the input. */
public final class RuntimeProfilePresets {

    private RuntimeProfilePresets() {}

    public static RuntimeProfile create(String gameId, RuntimeProfilePreset preset) {
        if (preset == null || preset == RuntimeProfilePreset.CUSTOM) {
            throw new IllegalArgumentException("CUSTOM requires an existing profile");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("MESA_SHADER_CACHE_DISABLE", "false");
        environment.put("WINEESYNC", "1");
        switch (preset) {
            case RECOMMENDED:
                environment.put("ZINK_DESCRIPTORS", "lazy");
                return profile(gameId, "wine-9.3-vanilla-wow64", "turnip", "dxvk",
                    "alsa", "1280x720", "INTERMEDIATE", environment, "xinput");
            case STABLE:
                return profile(gameId, "wine-9.0-staging-wow64", "turnip", "dxvk",
                    "alsa", "1280x720", "INTERMEDIATE", environment, "xinput");
            case COMPATIBILITY:
                environment.put("MESA_GL_VERSION_OVERRIDE", "4.5");
                return profile(gameId, "wine-8.18-staging-wow64", "virgl",
                    "wined3d", "alsa", "1024x768", "STABILITY",
                    environment, "dinput");
            default:
                throw new IllegalArgumentException("Unsupported preset: " + preset);
        }
    }

    public static RuntimeProfile apply(RuntimeProfile current, RuntimeProfilePreset preset) {
        if (current == null || preset == null) {
            throw new IllegalArgumentException("current and preset must not be null");
        }
        return preset == RuntimeProfilePreset.CUSTOM ? current : create(current.getId(), preset);
    }

    private static RuntimeProfile profile(String id, String wine, String graphics,
                                          String dx, String audio, String resolution,
                                          String box64, Map<String, String> environment,
                                          String input) {
        return new RuntimeProfile(id, wine, graphics, dx, audio, resolution, box64,
            environment, input, Collections.emptyMap());
    }
}
