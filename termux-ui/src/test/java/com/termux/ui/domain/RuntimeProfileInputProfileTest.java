package com.termux.ui.domain;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;

import org.junit.Test;

public class RuntimeProfileInputProfileTest {

    private static RuntimeProfile profile() {
        return RuntimeProfilePresets.create("game-1", RuntimeProfilePreset.RECOMMENDED);
    }

    @Test
    public void withInputProfileIdReplacesOnlyTheInputSelection() {
        RuntimeProfile base = profile();
        RuntimeProfile changed = base.withInputProfileId("dinput:12");

        assertNotSame(base, changed);
        assertEquals("dinput:12", changed.getInputProfileId());
        assertEquals(base.getId(), changed.getId());
        assertEquals(base.getWinePackage(), changed.getWinePackage());
        assertEquals(base.getGraphicsDriver(), changed.getGraphicsDriver());
        assertEquals(base.getEnvironment(), changed.getEnvironment());
        assertEquals(base.getLaunchExecutionMode(), changed.getLaunchExecutionMode());
        assertEquals(base.getRuntimeBackendType(), changed.getRuntimeBackendType());
        assertEquals(base.getContainerId(), changed.getContainerId());
        assertEquals(base.getComponentVersions(), changed.getComponentVersions());
    }

    @Test
    public void withInputProfileIdAcceptsExactlyTheLaunchSpecEncoding() {
        for (String valid : new String[] {"xinput", "dinput", "xinput:1", "dinput:999999"}) {
            assertEquals(valid, profile().withInputProfileId(valid).getInputProfileId());
        }
        for (String invalid : new String[] {null, "", "xinput:", "xinput:0", "xinput:01",
            "xinput:1000000", "xinput:-1", "xinput:a", "winput:1", "XINPUT", "xinput:1 ",
            " xinput", "xinput:1:2"}) {
            try {
                profile().withInputProfileId(invalid);
                throw new AssertionError("accepted " + invalid);
            } catch (IllegalArgumentException expected) {
                assertEquals("invalidInputProfileId", expected.getMessage());
            }
        }
    }
}
