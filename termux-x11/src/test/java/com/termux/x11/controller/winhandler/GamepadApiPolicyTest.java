package com.termux.x11.controller.winhandler;

import org.junit.Test;
import static org.junit.Assert.*;

public class GamepadApiPolicyTest {
    @Test public void allApiCombinationsKeepMappingIndependent() {
        GamepadApiPolicy policy = new GamepadApiPolicy();
        for (boolean xinput : new boolean[] {false, true}) {
            for (boolean dinput : new boolean[] {false, true}) {
                policy.setEnabled(xinput, dinput);
                assertEquals(xinput, policy.register(100, true));
                assertEquals(dinput, policy.register(200, false));
                assertEquals(xinput, policy.allows(100));
                assertEquals(dinput, policy.allows(200));
                for (byte mapper : new byte[] {0, 1}) {
                    int flags = policy.flags(mapper);
                    assertEquals(xinput, (flags & 4) != 0);
                    assertEquals(dinput, (flags & 8) != 0);
                    assertEquals(mapper == 1 ? 2 : 1, flags & 3);
                }
            }
        }
        assertFalse(policy.allows(300));
    }

    @Test public void disablingAnApiRevokesExistingPollingAndPushClients() {
        GamepadApiPolicy policy = new GamepadApiPolicy();
        policy.register(100, true);
        policy.register(200, false);
        policy.setEnabled(false, true);
        assertFalse(policy.allows(100));
        assertTrue(policy.allows(200));
        policy.setEnabled(true, false);
        assertTrue(policy.allows(100));
        assertFalse(policy.allows(200));
    }

    @Test public void releaseAndNewBridgeDoNotReuseClients() {
        GamepadApiPolicy policy = new GamepadApiPolicy();
        policy.register(100, true);
        policy.register(200, false);
        policy.remove(100);
        assertFalse(policy.allows(100));
        assertTrue(policy.allows(200));
        policy.clear();
        policy.setEnabled(false, true);
        assertFalse(policy.allows(200));
        assertFalse(policy.register(200, true));
        assertTrue(policy.register(200, false));
    }

    @Test public void requestRetainsApiAndSubscriptionIndependently() {
        for (int api = 0; api <= 1; api++) {
            for (int notify = 0; notify <= 1; notify++) {
                LegacyGamepadProtocol.Request request = LegacyGamepadProtocol.readRequest(
                    java.nio.ByteBuffer.wrap(new byte[] {(byte) api, (byte) notify}));
                assertEquals(api == 1, request.xinput);
                assertEquals(notify == 1, request.notify);
            }
        }
    }
}
