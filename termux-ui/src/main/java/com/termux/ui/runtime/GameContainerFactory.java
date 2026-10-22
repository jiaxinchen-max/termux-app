package com.termux.ui.runtime;

import androidx.annotation.Nullable;

import com.termux.ui.data.GameStoragePaths;
import com.termux.ui.domain.GameContainer;
import com.termux.ui.domain.RuntimeProfile;
import com.termux.ui.domain.RuntimeProfilePreset;
import com.termux.ui.domain.RuntimeProfilePresets;
import com.termux.ui.domain.RuntimeTranslator;

/** Creates the global GLIBC runtime and per-game isolated runtime containers. */
public final class GameContainerFactory {
    private GameContainerFactory() { }

    /** Legacy convenience -- see GameContainerProfileResolver.resolve(profile, container)'s
     *  2-arg overload for the same custom-wine-* caveat (falls back to BOX64 bucket with no
     *  GameStoragePaths to consult the registry). */
    public static GameContainer fromProfile(RuntimeProfile profile) {
        return fromProfile(profile, null);
    }

    public static GameContainer fromProfile(RuntimeProfile profile, @Nullable GameStoragePaths paths) {
        if (GameContainer.DEFAULT_ID.equals(profile.getContainerId())) {
            return globalGlibcFromProfile(profile);
        }
        RuntimeTranslator translator = GameContainerProfileResolver.resolveTranslator(
            profile.getWinePackage(), paths);
        return new GameContainer(profile.getContainerId(), "Independent container",
            profile.getRuntimeBackendType(), profile.getRootfsPackage(), translator,
            profile.getWinePackage(), profile.getGraphicsDriver(), profile.getDxWrapper(),
            profile.getAudioDriver(), profile.getResolution(), profile.getBox64Preset(),
            profile.getEnvironment());
    }

    /** The default id is reserved for the one shared GLIBC prefix used by every game. */
    public static GameContainer globalGlibcFromProfile(RuntimeProfile profile) {
        RuntimeProfile source = profile.getRuntimeBackendType() ==
            com.termux.ui.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX
            ? profile : RuntimeProfilePresets.create(profile.getId(),
                RuntimeProfilePreset.RECOMMENDED);
        return new GameContainer(GameContainer.DEFAULT_ID, "Global GLIBC",
            com.termux.ui.domain.GameRuntimeBackendType.GLIBC_TERMUX_BOX, "",
            RuntimeTranslator.BOX64, source.getWinePackage(), source.getGraphicsDriver(),
            source.getDxWrapper(), source.getAudioDriver(), source.getResolution(),
            source.getBox64Preset(), source.getEnvironment());
    }
}
