package com.termux.ui.wine.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Deterministic game-level baselines; CUSTOM intentionally preserves the input. */
public final class RuntimeProfilePresets {

    public static final String NATIVE_DEFAULT_GRAPHICS = "native-turnip-v6.5";
    public static final String NATIVE_DEFAULT_DX = "native-dxvk-gplasync-2.3";
    public static final String DEFAULT_CONTROLLER_DLL_LOAD_ORDER = "native,builtin";

    private RuntimeProfilePresets() {}

    public static RuntimeProfile create(String gameId, RuntimeProfilePreset preset) {
        if (preset == null || preset == RuntimeProfilePreset.CUSTOM) {
            throw new IllegalArgumentException("CUSTOM requires an existing profile");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("MESA_SHADER_CACHE_DISABLE", "false");
        environment.put("WINEESYNC", "1");
        environment.put("GAMES_DLL_DINPUT", DEFAULT_CONTROLLER_DLL_LOAD_ORDER);
        environment.put("GAMES_DLL_XINPUT", DEFAULT_CONTROLLER_DLL_LOAD_ORDER);
        switch (preset) {
            case RECOMMENDED:
                environment.put("ZINK_DESCRIPTORS", "lazy");
                return profile(gameId, "glibc_wine_9.3-vanilla", NATIVE_DEFAULT_GRAPHICS, NATIVE_DEFAULT_DX,
                    "alsa", "1280x720", "INTERMEDIATE", environment, "xinput");
            case STABLE:
                return profile(gameId, "glibc_wine_9.0-staging", NATIVE_DEFAULT_GRAPHICS, NATIVE_DEFAULT_DX,
                    "alsa", "1280x720", "INTERMEDIATE", environment, "xinput");
            case COMPATIBILITY:
                environment.put("MESA_GL_VERSION_OVERRIDE", "4.5");
                return profile(gameId, "glibc_wine_8.18-staging", "virgl",
                    "wined3d", "alsa", "1024x768", "STABILITY",
                    environment, "xinput");
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
