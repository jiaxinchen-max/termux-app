package com.termux.x11.input;

import org.junit.Test;
import java.lang.reflect.Proxy;
import static org.junit.Assert.assertEquals;

public class ScrollUnitTest {
    @Test
    public void gesturePixelsBecomeV120WithoutChangingHardwareWheelUnits() {
        float[] wheel = new float[2];
        InputStub stub = (InputStub) Proxy.newProxyInstance(InputStub.class.getClassLoader(),
            new Class<?>[]{InputStub.class}, (proxy, method, args) -> {
                if (method.getName().equals("sendMouseWheelEvent")) {
                    wheel[0] = (Float) args[0];
                    wheel[1] = (Float) args[1];
                }
                return method.getReturnType() == boolean.class ? false : null;
            });
        InputEventSender sender = new InputEventSender(stub);
        sender.sendMouseScrollPixels(15, -30);
        assertEquals(120, wheel[0], 0);
        assertEquals(-240, wheel[1], 0);
        sender.sendMouseWheelEvent(120, -120);
        assertEquals(120, wheel[0], 0);
        assertEquals(-120, wheel[1], 0);
        sender.sendMouseScrollPixels(0.5f, -0.25f);
        assertEquals(4, wheel[0], 0);
        assertEquals(-2, wheel[1], 0);
    }
}
