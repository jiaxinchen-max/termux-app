package com.termux.x11.controller.winhandler;

import android.content.Context;
import com.termux.x11.LorieViewRuntimeApi;
import com.termux.x11.controller.inputcontrols.ControlsProfile;
import com.termux.x11.controller.widget.InputControlsView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GamepadRuntimeSwitchTest {
    @Test public void runtimeApiChangesReleaseHeldControlsAndKeepTheOtherApiConnected() {
        Context context = RuntimeEnvironment.getApplication();
        ControlsProfile profile = new ControlsProfile(context, 3);
        profile.setName("Pad");
        ReflectionHelpers.setField(profile, "virtualGamepad", true);
        profile.getGamepadState().buttons = 1;
        profile.getGamepadState().thumbLX = 0.5f;
        InputControlsView controls = new InputControlsView(context);
        controls.setProfile(profile);
        LorieViewRuntimeApi.WinHandlerHost host = (LorieViewRuntimeApi.WinHandlerHost) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[] {LorieViewRuntimeApi.WinHandlerHost.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("getInputControlsView")) return controls;
                throw new AssertionError(method.getName());
            });
        CapturingBridge bridge = new CapturingBridge(host);
        bridge.register(100, true);
        bridge.register(200, false);
        bridge.packets.clear();

        bridge.gamepadHandler.setInputApis(false, true);
        assertEquals(2, bridge.packets.size());
        assertNeutralThenUnavailable(bridge.packets, 100);
        bridge.packets.clear();
        bridge.gamepadHandler.sendGamepadStateForAllControllers();
        assertEquals(1, bridge.packets.size());
        assertEquals(200, bridge.packets.get(0).port);
        assertEquals(1, bridge.packets.get(0).bytes[1]);
        assertEquals(1, bridge.packets.get(0).bytes[6]);

        // A disabled DLL can refresh its request without losing its subscription.
        bridge.register(100, true);

        bridge.packets.clear();
        bridge.gamepadHandler.setInputApis(true, false);
        assertNeutralThenUnavailable(bridge.packets, 200);
        bridge.packets.clear();
        bridge.gamepadHandler.sendGamepadStateForAllControllers();
        assertEquals(1, bridge.packets.size());
        assertEquals(100, bridge.packets.get(0).port);
        assertEquals(1, bridge.packets.get(0).bytes[1]);

        bridge.gamepadHandler.setInputApis(true, true);
        bridge.packets.clear();
        bridge.gamepadHandler.sendGamepadStateForAllControllers();
        assertEquals(2, bridge.packets.size());
    }

    private static void assertNeutralThenUnavailable(List<Packet> packets, int port) {
        assertEquals(2, packets.size());
        Packet neutral = packets.get(0), unavailable = packets.get(1);
        assertEquals(port, neutral.port);
        assertEquals(port, unavailable.port);
        assertEquals(64, neutral.bytes.length);
        assertEquals(RequestCodes.GET_GAMEPAD_STATE, neutral.bytes[0]);
        assertEquals(1, neutral.bytes[1]);
        assertEquals(3, ByteBuffer.wrap(neutral.bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(2));
        // Buttons, dpad, axes and triggers are all released for an already-acquired DirectInput device.
        for (int i = 6; i < 21; i++) assertEquals("state byte " + i, i == 8 ? -1 : 0, neutral.bytes[i]);
        assertEquals(RequestCodes.GET_GAMEPAD_STATE, unavailable.bytes[0]);
        assertEquals(0, unavailable.bytes[1]);
    }

    private static final class Packet {
        final int port;
        final byte[] bytes;
        Packet(int port, byte[] bytes) { this.port = port; this.bytes = bytes; }
    }

    private static final class CapturingBridge extends WinHandler {
        final List<Packet> packets = new ArrayList<>();
        CapturingBridge(LorieViewRuntimeApi.WinHandlerHost host) { super(host); }
        @Override protected void addAction(Runnable action) { action.run(); }
        @Override boolean isInitReceived() { return true; }
        @Override protected boolean sendPacket(int port, int length) {
            packets.add(new Packet(port, java.util.Arrays.copyOf(sendData.array(), length)));
            return true;
        }
        void register(int port, boolean xinput) {
            receiveData.rewind();
            receiveData.put((byte) (xinput ? 1 : 0)).put((byte) 1).rewind();
            gamepadHandler.handleGetGamepadRequest(port);
        }
    }
}
