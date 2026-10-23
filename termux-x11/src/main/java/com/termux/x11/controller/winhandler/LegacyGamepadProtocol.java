package com.termux.x11.controller.winhandler;

import com.termux.x11.controller.inputcontrols.GamepadState;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Wire format of the patched xinput/dinput DLLs shipped with the app (wine.tar.gz): one controller per client, packets
 * of at most 64 bytes, little endian. The multi-slot protocol of {@link GamepadHandler} needs different DLLs.
 *
 * GET_GAMEPAD reply: [8] then either [int deviceId][byte mapperType][int nameLength][name] or [int 0] (no controller).
 * GET_GAMEPAD_STATE reply and pushes: [9][byte enabled] then, when enabled, [int deviceId][GamepadState.writeTo].
 */
final class LegacyGamepadProtocol {
    /** The DLLs receive into a 64 byte buffer; a longer datagram makes the receive fail and ends their read thread. */
    static final int PACKET_LENGTH = 64;
    private static final int MAX_NAME_LENGTH = PACKET_LENGTH - 1 - 4 - 1 - 4;

    private LegacyGamepadProtocol() {}

    /**
     * Whether a GET_GAMEPAD request asks to be notified of state changes. The request is [8][isXInput][notify] and the
     * buffer is positioned after the request code, as in {@link WinHandler}.
     */
    static boolean isNotifyRequest(ByteBuffer request) {
        request.get(); // isXInput
        return request.get() == 1;
    }

    static void writeGamepadInfo(ByteBuffer out, boolean enabled, int deviceId, byte mapperType, String name) {
        out.put(RequestCodes.GET_GAMEPAD);
        if (!enabled) {
            out.putInt(0);
            return;
        }
        byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        int length = Math.min(bytes.length, MAX_NAME_LENGTH);
        out.putInt(deviceId);
        out.put(mapperType);
        out.putInt(length);
        out.put(bytes, 0, length);
    }

    static void writeGamepadState(ByteBuffer out, boolean enabled, int deviceId, GamepadState state) {
        out.put(RequestCodes.GET_GAMEPAD_STATE);
        out.put((byte) (enabled ? 1 : 0));
        if (!enabled) return;
        out.putInt(deviceId);
        state.writeTo(out);
    }
}
