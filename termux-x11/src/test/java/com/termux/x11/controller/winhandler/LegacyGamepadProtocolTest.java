package com.termux.x11.controller.winhandler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.termux.x11.controller.inputcontrols.GamepadState;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Pins the byte layout that the patched xinput/dinput DLLs shipped with the app parse. */
public class LegacyGamepadProtocolTest {
    private static ByteBuffer buffer() {
        return ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static ByteBuffer afterCode(int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) data[i] = (byte) bytes[i];
        ByteBuffer request = ByteBuffer.wrap(data);
        request.get();
        return request;
    }

    @Test
    public void gamepadInfoCarriesIdMapperAndName() {
        ByteBuffer out = buffer();
        LegacyGamepadProtocol.writeGamepadInfo(out, true, 0x0102, GamepadHandler.DINPUT_MAPPER_TYPE_XINPUT, "Pad");

        assertEquals(1 + 4 + 1 + 4 + 3, out.position());
        assertEquals(RequestCodes.GET_GAMEPAD, out.get(0));
        assertEquals(0x0102, out.getInt(1));
        assertEquals(GamepadHandler.DINPUT_MAPPER_TYPE_XINPUT, out.get(5));
        assertEquals(3, out.getInt(6));
        assertEquals("Pad", new String(out.array(), 10, 3, StandardCharsets.UTF_8));
    }

    @Test
    public void gamepadInfoWithoutControllerIsAZeroId() {
        ByteBuffer out = buffer();
        LegacyGamepadProtocol.writeGamepadInfo(out, false, 7, GamepadHandler.DINPUT_MAPPER_TYPE_STANDARD, "ignored");

        assertEquals(1 + 4, out.position());
        assertEquals(RequestCodes.GET_GAMEPAD, out.get(0));
        assertEquals(0, out.getInt(1));
    }

    @Test
    public void longNamesStayInsideThe64ByteReceiveBufferOfTheDlls() {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 200; i++) name.append('x');
        ByteBuffer out = buffer();
        LegacyGamepadProtocol.writeGamepadInfo(out, true, 1, GamepadHandler.DINPUT_MAPPER_TYPE_XINPUT, name.toString());

        assertTrue("packet is " + out.position() + " bytes", out.position() <= LegacyGamepadProtocol.PACKET_LENGTH);
        assertEquals(out.position() - 10, out.getInt(6));
    }

    @Test
    public void enabledStateIsCodeFlagIdAndTheFifteenByteState() {
        GamepadState state = new GamepadState();
        state.buttons = 0x0005;
        state.dpad[1] = true; // pov hat 2
        state.thumbLX = 1f;
        state.thumbLY = -1f;
        state.triggerR = 1f;
        ByteBuffer out = buffer();
        LegacyGamepadProtocol.writeGamepadState(out, true, 3, state);

        assertEquals(1 + 1 + 4 + 15, out.position());
        assertEquals(RequestCodes.GET_GAMEPAD_STATE, out.get(0));
        assertEquals(1, out.get(1));
        assertEquals(3, out.getInt(2));
        // What the DLL reads: buttons at 6, hat at 8, the four thumb axes from 9.
        assertEquals(0x0005, out.getShort(6));
        assertEquals(2, out.get(8));
        assertEquals(Short.MAX_VALUE, out.getShort(9));
        assertEquals(-Short.MAX_VALUE, out.getShort(11));
        assertEquals(Short.MAX_VALUE, out.getShort(19));
        assertTrue(out.position() <= LegacyGamepadProtocol.PACKET_LENGTH);
    }

    @Test
    public void disabledStateIsJustCodeAndZeroFlag() {
        ByteBuffer out = buffer();
        LegacyGamepadProtocol.writeGamepadState(out, false, 3, new GamepadState());

        assertEquals(2, out.position());
        assertEquals(RequestCodes.GET_GAMEPAD_STATE, out.get(0));
        assertEquals(0, out.get(1));
    }

    @Test
    public void notifyFlagIsTheSecondByteOfTheRequest() {
        // [8][isXInput][notify], as sent by get_gamepad_request() of the DLLs; the code byte is already consumed.
        assertTrue(LegacyGamepadProtocol.isNotifyRequest(afterCode(8, 1, 1)));
        assertTrue(!LegacyGamepadProtocol.isNotifyRequest(afterCode(8, 1, 0)));
        assertTrue(!LegacyGamepadProtocol.isNotifyRequest(afterCode(8, 0, 0)));
        assertTrue(LegacyGamepadProtocol.isNotifyRequest(afterCode(8, 0, 1)));
    }
}
