package com.termux.x11.controller.winhandler;

import android.content.SharedPreferences;

public class GamepadPlayerConfig {
    public static final byte MODE_EXTERNAL_CONTROLLER = 0;
    public static final byte MODE_CONTROLS_PROFILE = 1;
    public final byte mode;
    public final String name;
    public final boolean vibration;

    public GamepadPlayerConfig(String values) {
        if (values == null || values.isEmpty()) {
            mode = MODE_EXTERNAL_CONTROLLER;
            name = "";
            vibration = false;
            return;
        }
        // Parse key=value format: mode=0,name=Xbox Controller,vibration=true
        byte parsedMode = MODE_EXTERNAL_CONTROLLER;
        String parsedName = "";
        boolean parsedVibration = false;

        String[] pairs = values.split(",");
        for (String pair : pairs) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                switch (kv[0].trim()) {
                    case "mode":
                        parsedMode = Byte.parseByte(kv[1].trim());
                        break;
                    case "name":
                        parsedName = kv[1].trim();
                        break;
                    case "vibration":
                        parsedVibration = Boolean.parseBoolean(kv[1].trim());
                        break;
                }
            }
        }

        this.mode = parsedMode;
        this.name = parsedName;
        this.vibration = parsedVibration;
    }
}
