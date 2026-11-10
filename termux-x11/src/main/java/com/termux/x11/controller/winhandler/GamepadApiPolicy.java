package com.termux.x11.controller.winhandler;

import java.util.concurrent.ConcurrentHashMap;

/** API selection and per-client identity for the single-controller UDP protocol. */
final class GamepadApiPolicy {
    private volatile byte apiFlags = 0x0c;
    private final ConcurrentHashMap<Integer, Boolean> clients = new ConcurrentHashMap<>();

    void setEnabled(boolean xinput, boolean dinput) {
        apiFlags = (byte) ((xinput ? 0x04 : 0) | (dinput ? 0x08 : 0));
    }

    boolean register(int port, boolean isXinput) {
        clients.put(port, isXinput);
        return isEnabled(isXinput);
    }

    boolean allows(int port) {
        Boolean api = clients.get(port);
        return api != null && isEnabled(api);
    }

    private boolean isEnabled(boolean isXinput) { return (apiFlags & (isXinput ? 0x04 : 0x08)) != 0; }

    byte flags(byte mapper) {
        return (byte) ((mapper == 1 ? 0x02 : 0x01) | apiFlags);
    }

    void remove(int port) { clients.remove(port); }
    void clear() { clients.clear(); }
}
