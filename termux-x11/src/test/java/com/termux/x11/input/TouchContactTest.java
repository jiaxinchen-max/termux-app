package com.termux.x11.input;

import org.junit.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TouchContactTest {
    @Test
    public void cancellationEndsEveryLiveIdAtItsLastPositionWithoutMouseEvents() {
        List<String> contacts = new ArrayList<>();
        InputStub stub = (InputStub) Proxy.newProxyInstance(InputStub.class.getClassLoader(),
            new Class<?>[]{InputStub.class}, (proxy, method, args) -> {
                if (method.getName().equals("sendTouchEvent")) {
                    contacts.add(args[0] + ":" + args[1] + ":" + args[2] + ":" + args[3]);
                } else {
                    fail("Direct touch was translated into " + method.getName());
                }
                return null;
            });
        InputEventSender sender = new InputEventSender(stub);
        sender.sendTouchContactEvent(18, 2, 100, 200);
        sender.sendTouchContactEvent(18, 31, 300, 400);
        sender.sendTouchContactEvent(19, 2, 110, 220);
        contacts.clear();
        sender.cancelActiveTouchEvents();
        assertEquals(List.of("20:2:110:220", "20:31:300:400"), contacts);
        contacts.clear();
        sender.cancelActiveTouchEvents();
        sender.sendTouchContactEvent(19, 31, 999, 999);
        sender.sendTouchContactEvent(20, 31, 999, 999);
        assertEquals(List.of(), contacts);
        // Reusing an Android ID after cancellation must create a fresh contact.
        sender.sendTouchContactEvent(18, 31, 20, 30);
        sender.cancelActiveTouchEvents();
        assertEquals(List.of("18:31:20:30", "20:31:20:30"), contacts);
    }
}
