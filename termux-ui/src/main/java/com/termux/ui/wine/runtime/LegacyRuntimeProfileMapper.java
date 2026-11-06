package com.termux.ui.wine.runtime;

import com.termux.ui.wine.api.LegacyRuntimeConfiguration;
import com.termux.ui.wine.domain.RuntimeEnvironment;
import com.termux.ui.wine.domain.RuntimeProfile;

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
