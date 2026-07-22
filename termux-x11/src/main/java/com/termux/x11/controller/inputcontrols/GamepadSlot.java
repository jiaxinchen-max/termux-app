package com.termux.x11.controller.inputcontrols;

public interface GamepadSlot {
    String getName();
    short getVendorId();
    short getProductId();
    GamepadState getGamepadState();
    GamepadVibration getGamepadVibration();
}
