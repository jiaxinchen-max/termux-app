package com.termux.localgames.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.termux.localgames.api.LegacyRuntimeConfiguration;
import com.termux.localgames.runtime.LegacyRuntimeProfileMapper;

import org.junit.Test;

import java.util.List;
import java.util.Map;

public class RuntimeProfileConfigurationTest {

    @Test
    public void presetsAndCustomHaveStableSemantics() {
        RuntimeProfile recommended = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);
        RuntimeProfile compatibility = RuntimeProfilePresets.apply(recommended,
            RuntimeProfilePreset.COMPATIBILITY);

        assertEquals("wine-9.3-vanilla-wow64", recommended.getWinePackage());
        assertEquals(GameRuntimeBackendType.GLIBC_TERMUX_BOX,
            recommended.getRuntimeBackendType());
        assertEquals("virgl", compatibility.getGraphicsDriver());
        assertSame(compatibility, RuntimeProfilePresets.apply(compatibility,
            RuntimeProfilePreset.CUSTOM));
    }

    @Test
    public void diffUsesStableFieldThenSortedMapOrder() {
        RuntimeProfile before = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.RECOMMENDED);
        RuntimeProfile after = RuntimeProfilePresets.create("game-1",
            RuntimeProfilePreset.COMPATIBILITY);

        List<RuntimeProfileChange> changes = RuntimeProfileDiff.between(before, after);

        assertEquals("winePackage", changes.get(0).getField());
        assertEquals("graphicsDriver", changes.get(1).getField());
        assertTrue(changes.get(changes.size() - 1).getField().startsWith("environment."));
    }

    @Test
    public void environmentParsesQuotedLegacyValuesAndRoundTrips() {
        Map<String, String> parsed = RuntimeEnvironment.parse(
            "WINEESYNC=1 NOTE=\"value with spaces\" PATH='a b'");

        assertEquals("value with spaces", parsed.get("NOTE"));
        assertEquals(parsed, RuntimeEnvironment.parse(RuntimeEnvironment.format(parsed)));
    }

    @Test
    public void mapsDetachedLegacyConfigurationToGameOwnedProfile() {
        LegacyRuntimeConfiguration legacy = new LegacyRuntimeConfiguration("container-1",
            "Existing", "wine-9.0-staging-wow64", "vortek,gladio", "dxvk",
            "alsa", "1366x768", "INTERMEDIATE", "WINEESYNC=1", "xinput");

        RuntimeProfile mapped = new LegacyRuntimeProfileMapper().map("game-1", legacy);

        assertEquals("game-1", mapped.getId());
        assertEquals("vortek,gladio", mapped.getGraphicsDriver());
        assertEquals("1", mapped.getEnvironment().get("WINEESYNC"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsInvalidEnvironmentKeys() {
        RuntimeEnvironment.parse("BAD-NAME=1");
    }
}
