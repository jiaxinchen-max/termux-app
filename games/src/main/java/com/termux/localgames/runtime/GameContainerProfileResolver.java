package com.termux.localgames.runtime;

import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.RuntimeProfile;

import java.util.LinkedHashMap;
import java.util.Map;

/** Applies the one container bound by a game before preflight or launch-spec creation. */
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
        environment.put("GAMES_RUNTIME_TRANSLATOR", container.getTranslator().getStorageValue());
        return new RuntimeProfile(gameProfile.getId(), container.getWinePackage(),
            container.getGraphicsDriver(), container.getDxWrapper(), container.getAudioDriver(),
            container.getResolution(), container.getBox64Preset(), environment,
            gameProfile.getInputProfileId(), gameProfile.getLaunchExecutionMode(),
            gameProfile.getComponentVersions(), container.getBackendType(),
            container.getRootfsPackage(), container.getId());
    }
}
