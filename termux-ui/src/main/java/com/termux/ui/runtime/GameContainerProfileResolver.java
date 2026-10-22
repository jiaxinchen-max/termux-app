package com.termux.ui.runtime;

import androidx.annotation.Nullable;

import com.termux.ui.data.FileCustomRuntimeComponentRepository;
import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.CustomRuntimeComponent;
import com.termux.ui.domain.GameContainer;
import com.termux.ui.domain.RuntimeProfile;
import com.termux.ui.domain.RuntimeTranslator;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies the runtime substrate supplied by one bound container before preflight or launch.
 *
 * <p>A container owns its prefix and backend. Wine, graphics and performance selections remain
 * on {@link RuntimeProfile}, so two games can share GLIBC components without silently sharing
 * their launch parameters.</p>
 */
public final class GameContainerProfileResolver {

    /** Legacy convenience for callers (unit tests) that never select a custom-installed Wine
     *  build -- a custom-wine-* winePackage reaching this overload silently falls back to the
     *  BOX64 bucket (same as the pre-custom-component behavior), since there is no
     *  GameStoragePaths available here to consult the registry. Every production call site should
     *  use the 3-arg overload instead. */
    public RuntimeProfile resolve(RuntimeProfile gameProfile, GameContainer container) {
        return resolve(gameProfile, container, null);
    }

    public RuntimeProfile resolve(RuntimeProfile gameProfile, GameContainer container,
                                  @Nullable GameStoragePaths paths) {
        if (gameProfile == null || container == null) {
            throw new IllegalArgumentException("profile_and_container_required");
        }
        if (!gameProfile.getContainerId().equals(container.getId())) {
            throw new IllegalArgumentException("game_container_binding_mismatch");
        }
        Map<String, String> environment = new LinkedHashMap<>(container.getEnvironment());
        environment.putAll(gameProfile.getEnvironment());
        // Derived from the profile's current winePackage, matching GameContainerFactory's own
        // derivation -- not read from container.getTranslator(), which is only set once at
        // container-creation time and would otherwise go stale the moment the profile's "Wine
        // package" picker changes without the game being re-imported.
        RuntimeTranslator translator = resolveTranslator(gameProfile.getWinePackage(), paths);
        environment.put("GAMES_RUNTIME_TRANSLATOR", translator.getStorageValue());
        if (gameProfile.getWinePackage().startsWith("custom-wine-")) {
            // Shell-side path override -- see rootfs_prefix_warmup.sh's resolve_rootfs_translator().
            // The destination is fixed by install_custom_rootfs_component.sh's own DEST
            // convention, so no further registry lookup is needed for the path itself.
            environment.put("GAMES_CUSTOM_WINE_PATH",
                "/opt/custom-wine/" + gameProfile.getWinePackage() + "/bin/wine");
            environment.put("GAMES_CUSTOM_WINEBOOT_PATH",
                "/opt/custom-wine/" + gameProfile.getWinePackage() + "/bin/wineboot");
        }
        return new RuntimeProfile(gameProfile.getId(), gameProfile.getWinePackage(),
            gameProfile.getGraphicsDriver(), gameProfile.getDxWrapper(), gameProfile.getAudioDriver(),
            gameProfile.getResolution(), gameProfile.getBox64Preset(), environment,
            gameProfile.getInputProfileId(), gameProfile.getLaunchExecutionMode(),
            gameProfile.getComponentVersions(), container.getBackendType(),
            container.getRootfsPackage(), container.getId());
    }

    /** Package-visible so GameContainerFactory can reuse the identical classification rule --
     *  duplicating this ternary independently (as both classes used to) is exactly how a third
     *  "custom-wine-" bucket would have silently diverged between the two call sites. */
    public static RuntimeTranslator resolveTranslator(String winePackage, @Nullable GameStoragePaths paths) {
        if (winePackage.startsWith("rootfs_hangover_")) return RuntimeTranslator.HANGOVER;
        if (winePackage.startsWith("rootfs_wine_")) return RuntimeTranslator.BOX64;
        if (winePackage.startsWith("custom-wine-") && paths != null) {
            try {
                CustomRuntimeComponent component = new FileCustomRuntimeComponentRepository(
                    paths.getCustomRuntimeComponentsDirectory()).find(winePackage)
                    .orElseThrow(() -> new IllegalArgumentException(
                        "custom_wine_not_registered:" + winePackage));
                // A null translator means this was installed via the GLIBC_TERMUX_BOX custom-wine
                // flow (see CustomRuntimeComponent's own doc comment) -- that backend has exactly
                // one translation path, so BOX64 is the only sensible value, same as every catalog
                // GLIBC wine id already falls into via this method's final fallback below. Do not
                // fold this into a single Optional.map(...).orElseThrow(...) chain: map() treats a
                // null-returning mapper as "absent", which would misreport a correctly-registered
                // GLIBC-origin component as unregistered.
                RuntimeTranslator translator = component.getTranslator();
                return translator != null ? translator : RuntimeTranslator.BOX64;
            } catch (IOException error) {
                throw new IllegalArgumentException(
                    "custom_wine_lookup_failed:" + winePackage, error);
            }
        }
        return RuntimeTranslator.BOX64;
    }
}
