package com.termux.localgames.runtime;

import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeTranslator;

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

    public RuntimeProfile resolve(RuntimeProfile gameProfile, GameContainer container) {
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
        RuntimeTranslator translator = gameProfile.getWinePackage().startsWith("hangover-")
            ? RuntimeTranslator.HANGOVER : RuntimeTranslator.BOX64;
        environment.put("GAMES_RUNTIME_TRANSLATOR", translator.getStorageValue());
        return new RuntimeProfile(gameProfile.getId(), gameProfile.getWinePackage(),
            gameProfile.getGraphicsDriver(), gameProfile.getDxWrapper(), gameProfile.getAudioDriver(),
            gameProfile.getResolution(), gameProfile.getBox64Preset(), environment,
            gameProfile.getInputProfileId(), gameProfile.getLaunchExecutionMode(),
            gameProfile.getComponentVersions(), container.getBackendType(),
            container.getRootfsPackage(), container.getId());
    }
}
