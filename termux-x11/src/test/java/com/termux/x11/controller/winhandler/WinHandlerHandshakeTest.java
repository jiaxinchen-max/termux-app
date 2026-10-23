package com.termux.x11.controller.winhandler;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class WinHandlerHandshakeTest {
    @Test
    public void startsNotReady() {
        assertFalse(new WinHandlerHandshake().isReady());
    }

    @Test
    public void initMakesItReady() {
        WinHandlerHandshake handshake = new WinHandlerHandshake();
        assertTrue(handshake.onRequest(RequestCodes.INIT));
        assertTrue(handshake.isReady());
    }

    @Test
    public void gamepadRequestBeforeInitIsAnImplicitInit() {
        // winhandler.exe sends INIT once; when it arrives before the port is bound the DLL's requests must still work.
        for (byte code : new byte[] { RequestCodes.GET_GAMEPAD, RequestCodes.GET_GAMEPAD_STATE }) {
            WinHandlerHandshake handshake = new WinHandlerHandshake();
            assertTrue("request " + code, handshake.onRequest(code));
            assertTrue(handshake.isReady());
        }
    }

    @Test
    public void unrelatedRequestsDoNotMakeItReady() {
        for (byte code : new byte[] { RequestCodes.GET_PROCESS, RequestCodes.CURSOR_POS_FEEDBACK,
                RequestCodes.RELEASE_GAMEPAD, RequestCodes.SET_GAMEPAD_STATE, RequestCodes.EXIT }) {
            WinHandlerHandshake handshake = new WinHandlerHandshake();
            assertFalse("request " + code, handshake.onRequest(code));
            assertFalse(handshake.isReady());
        }
    }

    @Test
    public void onlyTheFirstEvidenceReportsTheTransition() {
        WinHandlerHandshake handshake = new WinHandlerHandshake();
        assertTrue(handshake.onRequest(RequestCodes.GET_GAMEPAD));
        assertFalse(handshake.onRequest(RequestCodes.INIT));
        assertFalse(handshake.onRequest(RequestCodes.GET_GAMEPAD_STATE));
        assertTrue(handshake.isReady());
    }
}
