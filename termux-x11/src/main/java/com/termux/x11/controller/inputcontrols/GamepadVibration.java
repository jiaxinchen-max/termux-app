package com.termux.x11.controller.inputcontrols;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;

public class GamepadVibration {
    private final Vibrator[] vibrators = new Vibrator[2];
    private final short[] currentSpeed = new short[2];
    private final boolean[] vibrating = new boolean[2];

    protected GamepadVibration(Context context) {
        VibratorManager manager = null;
        Vibrator vibrator = null;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            manager = (VibratorManager)context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        }
        else vibrator = (Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);

        vibrators[0] = getVibratorAt(0, manager, vibrator);
        vibrators[1] = getVibratorAt(1, manager, vibrator);
    }

    protected GamepadVibration(String id) {
        VibratorManager manager = null;
        Vibrator vibrator = null;

        for (int deviceId : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(deviceId);
            if (device != null && device.getDescriptor().equals(id)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    manager = device.getVibratorManager();
                }
                else vibrator = device.getVibrator();
                break;
            }
        }

        vibrators[0] = getVibratorAt(0, manager, vibrator);
        vibrators[1] = getVibratorAt(1, manager, vibrator);
    }

    private static Vibrator getVibratorAt(int index, VibratorManager manager, Vibrator defaultVibrator) {
        Vibrator result;
        if (manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            int[] ids = manager.getVibratorIds();
            result = ids.length >= 2 && index < ids.length ? manager.getVibrator(index) : manager.getDefaultVibrator();
        }
        else result = defaultVibrator;
        return result != null && result.hasVibrator() ? result : null;
    }

    private void vibrateAt(int index, short newSpeed, int durationMs) {
        if (vibrators[index] == null) return;
        if (newSpeed == 0) {
            if (vibrating[index]) vibrators[index].cancel();
            vibrating[index] = false;
        }
        else if (newSpeed != currentSpeed[index]) {
            vibrators[index].vibrate(VibrationEffect.createOneShot(durationMs, newSpeed));
            vibrating[index] = true;
        }

        currentSpeed[index] = newSpeed;
    }

    private short parseAmplitude(int motorSpeed) {
        return (short)Math.round((motorSpeed / 65535.0f) * 255);
    }

    public void cancel() {
        for (byte i = 0; i < vibrators.length; i++) {
            if (vibrating[i]) {
                currentSpeed[i] = 0;
                vibrators[i].cancel();
                vibrating[i] = false;
            }
        }
    }

    public void vibrate(int leftMotorSpeed, int rightMotorSpeed, int durationMs) {
        if (vibrators[0] == null && vibrators[1] == null) return;

        short speedX = parseAmplitude(leftMotorSpeed);
        short speedY = parseAmplitude(rightMotorSpeed);

        if (vibrators[0] != null && vibrators[0] == vibrators[1]) {
            short avgSpeed = (short)((speedX + speedY) * 0.5f);
            vibrateAt(0, avgSpeed, durationMs);
        }
        else {
            if (vibrators[0] != null) vibrateAt(0, speedX, durationMs);
            if (vibrators[1] != null) vibrateAt(1, speedY, durationMs);
        }
    }
}
