package com.termux.localgames.runtime;

import com.termux.localgames.domain.GameContainer;
import com.termux.localgames.domain.RuntimeProfile;
import com.termux.localgames.domain.RuntimeProfilePreset;
import com.termux.localgames.domain.RuntimeProfilePresets;
import com.termux.localgames.domain.RuntimeTranslator;

/** Creates the global GLIBC runtime and per-game isolated runtime containers. */
public final class GameContainerFactory {
    private GameContainerFactory() { }

    public static GameContainer fromProfile(RuntimeProfile profile) {
        if (GameContainer.DEFAULT_ID.equals(profile.getContainerId())) {
            return globalGlibcFromProfile(profile);
        }
        RuntimeTranslator translator = profile.getWinePackage().startsWith("hangover-")
            ? RuntimeTranslator.HANGOVER : RuntimeTranslator.BOX64;
        return new GameContainer(profile.getContainerId(), "Independent container",
            profile.getRuntimeBackendType(), profile.getRootfsPackage(), translator,
            profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
            profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(),
            profile.getEnvironment());
    }

    /** The default id is reserved for the one shared GLIBC prefix used by every game. */
    public static GameContainer globalGlibcFromProfile(RuntimeProfile profile) {
        RuntimeProfile source = profile.getRuntimeBackendType() ==
            com.termux.localgames.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX
            ? profile : RuntimeProfilePresets.create(profile.getId(),
                RuntimeProfilePreset.RECOMMENDED);
        return new GameContainer(GameContainer.DEFAULT_ID, "Global GLIBC",
            com.termux.localgames.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX, "",
            RuntimeTranslator.BOX64, source.getWinePackage(), source.getGraphicsDriver(),
            source.getDxWrapper(), source.getAudioDriver(), source.getResolution(),
            source.getBox64Preset(), source.getEnvironment());
    }
}
