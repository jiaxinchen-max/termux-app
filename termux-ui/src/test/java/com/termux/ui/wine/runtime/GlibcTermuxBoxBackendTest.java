package com.termux.ui.wine.runtime;

import com.termux.ui.wine.domain.RuntimeProfile;

import org.junit.Test;

import java.util.Collections;
import java.util.Set;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GlibcTermuxBoxBackendTest {

    private final GlibcTermuxBoxBackend backend = new GlibcTermuxBoxBackend();

    @Test
    public void catalogWinePackageIsRequired() {
        Set<String> required = backend.requiredComponentIds(profile("glibc_wine_9.3-vanilla"));

        assertTrue(required.contains("glibc_wine_9.3-vanilla"));
    }

    @Test
    public void customWinePackageIsNotTreatedAsACatalogDownload() {
        Set<String> required = backend.requiredComponentIds(profile("custom-wine-my-build"));

        assertFalse(required.contains("custom-wine-my-build"));
    }

    private static RuntimeProfile profile(String winePackage) {
        return new RuntimeProfile("game-1", winePackage, "native-turnip-v6.5", "native-dxvk-gplasync-2.3", "alsa", "1280x720",
            "INTERMEDIATE", Collections.emptyMap(), "xinput", Collections.emptyMap());
    }
}
