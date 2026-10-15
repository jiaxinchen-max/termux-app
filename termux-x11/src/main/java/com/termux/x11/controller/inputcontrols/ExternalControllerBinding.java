package com.termux.x11.controller.inputcontrols;

import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

public class ExternalControllerBinding {
    public static final byte AXIS_X_NEGATIVE = -1;
    public static final byte AXIS_X_POSITIVE = -2;
    public static final byte AXIS_Y_NEGATIVE = -3;
    public static final byte AXIS_Y_POSITIVE = -4;
    public static final byte AXIS_Z_NEGATIVE = -5;
    public static final byte AXIS_Z_POSITIVE = -6;
    public static final byte AXIS_RZ_NEGATIVE = -7;
    public static final byte AXIS_RZ_POSITIVE = -8;
    private short keyCode;
    private Binding binding = Binding.NONE;

    public int getKeyCodeForAxis() {
        return keyCode;
    }

    public void setKeyCode(int keyCode) {
        this.keyCode = (short)keyCode;
    }

    public Binding getBinding() {
        return binding;
    }

    public void setBinding(Binding binding) {
        this.binding = binding;
    }

    public JSONObject toJSONObject() {
        try {
            JSONObject controllerBindingJSONObject = new JSONObject();
            controllerBindingJSONObject.put("keyCode", keyCode);
            controllerBindingJSONObject.put("binding", binding.name());
            return controllerBindingJSONObject;
        }
        catch (JSONException e) {
            return null;
        }
    }

    /**
     * 绑定列表显示的轴名称。用方向词而非 +/- 是因为 getKeyCodeForAxis 对 Y/RZ 做了取反
     * (AXIS_Y_NEGATIVE 对应实际向下),仅凭 +/- 极易让人误判方向而改错绑定。
     * 注意:此处仅影响显示,keyCode 保持不变,已保存的配置无需迁移。
     */
    @NonNull
    @Override
    public String toString() {
        switch (keyCode) {
            case AXIS_X_NEGATIVE:
                return "AXIS X LEFT";
            case AXIS_X_POSITIVE:
                return "AXIS X RIGHT";
            case AXIS_Y_NEGATIVE:
                return "AXIS Y DOWN";
            case AXIS_Y_POSITIVE:
                return "AXIS Y UP";
            case AXIS_Z_NEGATIVE:
                return "AXIS Z LEFT";
            case AXIS_Z_POSITIVE:
                return "AXIS Z RIGHT";
            case AXIS_RZ_NEGATIVE:
                return "AXIS RZ DOWN";
            case AXIS_RZ_POSITIVE:
                return "AXIS RZ UP";
            default:
                return KeyEvent.keyCodeToString(keyCode).replace("KEYCODE_", "").replace("_", " ");
        }
    }

    /**
     * 轴 + 方向 → 内部 keyCode。
     * 注意 Y/RZ 与 HAT_Y 的 Y 轴约定相反:AXIS_Y/RZ 沿用摇杆的 Y 向上(故 sign>0 即向下
     * 时返回 *_NEGATIVE),而 AXIS_HAT_Y 沿用 Android 的 Y 向下(sign>0 即 KEYCODE_DPAD_DOWN)。
     * 二者读写同源,功能自洽;若要统一需同步迁移已存配置中 -3/-4 与 -7/-8 的语义,勿单独改动。
     */
    public static int getKeyCodeForAxis(int axis, byte sign) {
        switch (axis) {
            case MotionEvent.AXIS_X:
                return sign > 0 ? AXIS_X_POSITIVE : AXIS_X_NEGATIVE;
            case MotionEvent.AXIS_Y:
                return sign > 0 ? AXIS_Y_NEGATIVE : AXIS_Y_POSITIVE;
            case MotionEvent.AXIS_Z:
                return sign > 0 ? AXIS_Z_POSITIVE : AXIS_Z_NEGATIVE;
            case MotionEvent.AXIS_RZ:
                return sign > 0 ? AXIS_RZ_NEGATIVE : AXIS_RZ_POSITIVE;
            case MotionEvent.AXIS_HAT_X:
                return sign > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
            case MotionEvent.AXIS_HAT_Y:
                return sign > 0 ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
            default:
                return KeyEvent.KEYCODE_UNKNOWN;
        }
    }
}
