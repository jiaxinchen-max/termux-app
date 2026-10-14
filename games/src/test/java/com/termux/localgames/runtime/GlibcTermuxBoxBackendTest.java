package com.termux.localgames.runtime;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.domain.RuntimeProfile;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

public class GlibcTermuxBoxBackendTest {

    private final GlibcTermuxBoxBackend backend = new GlibcTermuxBoxBackend();

    @Test
    public void catalogWinePackageIsRequired() {
        Set<String> required = backend.requiredComponentIds(profile("wine-9.3-vanilla-wow64"));

        assertTrue(required.contains("wine-9.3-vanilla-wow64"));
    }

    @Test
    public void customWinePackageIsNotTreatedAsACatalogDownload() {
        Set<String> required = backend.requiredComponentIds(profile("custom-wine-my-build"));

        assertFalse(required.contains("custom-wine-my-build"));
    }

    private static RuntimeProfile profile(String winePackage) {
        return new RuntimeProfile("game-1", winePackage, "turnip", "dxvk", "alsa", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput", Collections.emptyMap());
    }
}
