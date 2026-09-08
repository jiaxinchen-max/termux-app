package com.termux.localgames.runtime;

import com.termux.localgames.api.LegacyRuntimeConfiguration;
import com.termux.localgames.domain.RuntimeEnvironment;
import com.termux.localgames.domain.RuntimeProfile;

import java.util.Collections;

public final class LegacyRuntimeProfileMapper {

    public RuntimeProfile map(String gameId, LegacyRuntimeConfiguration source) {
        if (source == null) throw new IllegalArgumentException("source must not be null");
        return new RuntimeProfile(gameId, source.getWinePackage(), source.getGraphicsDriver(),
            source.getDxWrapper(), source.getAudioDriver(), source.getResolution(),
            source.getBox64Preset(), RuntimeEnvironment.parse(source.getEnvironment()),
            source.getInputProfileId(), Collections.emptyMap());
    }
}
