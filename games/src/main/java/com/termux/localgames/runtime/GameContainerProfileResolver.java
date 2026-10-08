package com.termux.localgames.runtime;

import androidx.annotation.Nullable;

import com.termux.localgames.data.FileCustomRuntimeComponentRepository;
import com.termux.localgames.data.GameStoragePaths;
import com.termux.localgames.domain.CustomRuntimeComponent;
import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeTranslator;

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
    static RuntimeTranslator resolveTranslator(String winePackage, @Nullable GameStoragePaths paths) {
        if (winePackage.startsWith("hangover-")) return RuntimeTranslator.HANGOVER;
        if (winePackage.startsWith("box64-wine")) return RuntimeTranslator.BOX64;
        if (winePackage.startsWith("custom-wine-") && paths != null) {
            try {
                return new FileCustomRuntimeComponentRepository(
                    paths.getCustomRuntimeComponentsDirectory()).find(winePackage)
                    .map(CustomRuntimeComponent::getTranslator)
                    .orElseThrow(() -> new IllegalArgumentException(
                        "custom_wine_not_registered:" + winePackage));
            } catch (IOException error) {
                throw new IllegalArgumentException(
                    "custom_wine_lookup_failed:" + winePackage, error);
            }
        }
        return RuntimeTranslator.BOX64;
    }
}
