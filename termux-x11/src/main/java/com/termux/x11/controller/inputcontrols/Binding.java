package com.termux.x11.controller.inputcontrols;

import android.content.Context;

import androidx.annotation.NonNull;

import com.termux.x11.R;
import com.termux.x11.controller.xserver.Pointer;
import com.termux.x11.controller.xserver.XKeycode;

import java.util.ArrayList;

public enum Binding {
    NONE, MOUSE_LEFT_BUTTON, MOUSE_MIDDLE_BUTTON, MOUSE_RIGHT_BUTTON, MOUSE_MOVE_LEFT, MOUSE_MOVE_RIGHT, MOUSE_MOVE_UP, MOUSE_MOVE_DOWN, MOUSE_SCROLL_UP, MOUSE_SCROLL_DOWN, MOUSE_SWAPL_R_BUTTONS, MOUSE_SHOW_INPUT_METHOD, KEY_UP, KEY_RIGHT, KEY_DOWN, KEY_LEFT, KEY_ENTER, KEY_ESC, KEY_BKSP, KEY_DEL, KEY_INSERT, KEY_TAB, KEY_SPACE, KEY_CTRL_L, KEY_CTRL_R, KEY_SHIFT_L, KEY_SHIFT_R, KEY_ALT_L, KEY_ALT_R, KEY_HOME, KEY_PRTSCN, KEY_PG_UP, KEY_PG_DOWN, KEY_END, KEY_CAPS_LOCK, KEY_NUM_LOCK, KEY_0, KEY_1, KEY_2, KEY_3, KEY_4, KEY_5, KEY_6, KEY_7, KEY_8, KEY_9, KEY_A, KEY_B, KEY_C, KEY_D, KEY_E, KEY_F, KEY_G, KEY_H, KEY_I, KEY_J, KEY_K, KEY_L, KEY_M, KEY_N, KEY_O, KEY_P, KEY_Q, KEY_R, KEY_S, KEY_T, KEY_U, KEY_V, KEY_W, KEY_X, KEY_Y, KEY_Z, KEY_BRACKET_LEFT, KEY_BRACKET_RIGHT, KEY_BACKSLASH, KEY_SLASH, KEY_SEMICOLON, KEY_COMMA, KEY_PERIOD, KEY_APOSTROPHE, KEY_GRAVE, KEY_KP_ADD, KEY_KP_SUBTRACT, KEY_MINUS, KEY_EQUALS, KEY_F1, KEY_F2, KEY_F3, KEY_F4, KEY_F5, KEY_F6, KEY_F7, KEY_F8, KEY_F9, KEY_F10, KEY_F11, KEY_F12, KEY_KP_0, KEY_KP_1, KEY_KP_2, KEY_KP_3, KEY_KP_4, KEY_KP_5, KEY_KP_6, KEY_KP_7, KEY_KP_8, KEY_KP_9, KEY_PAUSE, GAMEPAD_BUTTON_A, GAMEPAD_BUTTON_B, GAMEPAD_BUTTON_X, GAMEPAD_BUTTON_Y, GAMEPAD_BUTTON_L1, GAMEPAD_BUTTON_R1, GAMEPAD_BUTTON_SELECT, GAMEPAD_BUTTON_START, GAMEPAD_BUTTON_L3, GAMEPAD_BUTTON_R3, GAMEPAD_BUTTON_L2, GAMEPAD_BUTTON_R2, GAMEPAD_LEFT_THUMB_UP, GAMEPAD_LEFT_THUMB_RIGHT, GAMEPAD_LEFT_THUMB_DOWN, GAMEPAD_LEFT_THUMB_LEFT, GAMEPAD_RIGHT_THUMB_UP, GAMEPAD_RIGHT_THUMB_RIGHT, GAMEPAD_RIGHT_THUMB_DOWN, GAMEPAD_RIGHT_THUMB_LEFT, GAMEPAD_DPAD_UP, GAMEPAD_DPAD_RIGHT, GAMEPAD_DPAD_DOWN, GAMEPAD_DPAD_LEFT, KEY_VOL_UP, KEY_VOL_DOWN;
    public final XKeycode keycode;

    Binding() {
        XKeycode keycode;
        try {
            keycode = XKeycode.valueOf(name());
        }
        catch (IllegalArgumentException e) {
            keycode = XKeycode.KEY_NONE;
            switch (name()) {
                case "KEY_EQUALS":
                    keycode = XKeycode.KEY_EQUAL;
                    break;
                case "KEY_PG_UP":
                    keycode = XKeycode.KEY_PRIOR;
                    break;
                case "KEY_PG_DOWN":
                    keycode = XKeycode.KEY_NEXT;
                    break;
                case "KEY_VOL_UP":
                    keycode = XKeycode.KEY_CUSTOM_1;
                    break;
                case "KEY_VOL_DOWN":
                    keycode = XKeycode.KEY_CUSTOM_2;
                    break;
            }
        }
        this.keycode = keycode;
    }

    @NonNull
    @Override
    public String toString() {
        switch (this) {
            case KEY_SHIFT_L:
                return "L SHIFT";
            case KEY_SHIFT_R:
                return "R SHIFT";
            case KEY_CTRL_L:
                return "L CTRL";
            case KEY_CTRL_R:
                return "R CTRL";
            case KEY_ALT_L:
                return "L ALT";
            case KEY_ALT_R:
                return "R ALT";
            case KEY_BRACKET_LEFT:
                return "[";
            case KEY_BRACKET_RIGHT:
                return "]";
            case KEY_BACKSLASH:
                return "\\";
            case KEY_SLASH:
                return "/";
            case KEY_SEMICOLON:
                return ";";
            case KEY_COMMA:
                return ",";
            case KEY_PERIOD:
                return ".";
            case KEY_APOSTROPHE:
                return "'";
            case KEY_GRAVE:
                return "`/~";
            case KEY_MINUS:
                return "-";
            case KEY_EQUALS:
                return "=";
            case KEY_KP_ADD:
                return "KP +";
            case KEY_KP_SUBTRACT:
                return "KP -";
            case MOUSE_SWAPL_R_BUTTONS:
                return "SWAP L&R";
            case KEY_VOL_UP:
                return "VOL +";
            case KEY_VOL_DOWN:
                return "VOL -";
            default:
                return super.toString().replaceAll("^(MOUSE_)|(KEY_)|(GAMEPAD_)", "").replace("KP_", "NUMPAD_").replace("_", " ");
        }
    }

    public String getDisplayName(Context context) {
        switch (this) {
            case NONE: return context.getString(R.string.binding_label_none);
            case MOUSE_LEFT_BUTTON: return context.getString(R.string.binding_label_left_button);
            case MOUSE_MIDDLE_BUTTON: return context.getString(R.string.binding_label_middle_button);
            case MOUSE_RIGHT_BUTTON: return context.getString(R.string.binding_label_right_button);
            case MOUSE_MOVE_LEFT: return context.getString(R.string.binding_label_move_left);
            case MOUSE_MOVE_RIGHT: return context.getString(R.string.binding_label_move_right);
            case MOUSE_MOVE_UP: return context.getString(R.string.binding_label_move_up);
            case MOUSE_MOVE_DOWN: return context.getString(R.string.binding_label_move_down);
            case MOUSE_SCROLL_UP: return context.getString(R.string.binding_label_scroll_up);
            case MOUSE_SCROLL_DOWN: return context.getString(R.string.binding_label_scroll_down);
            case MOUSE_SWAPL_R_BUTTONS: return context.getString(R.string.binding_label_swap_lr);
            case MOUSE_SHOW_INPUT_METHOD: return context.getString(R.string.binding_label_show_input_method);
            case KEY_UP: return context.getString(R.string.binding_label_up);
            case KEY_RIGHT: return context.getString(R.string.binding_label_right);
            case KEY_DOWN: return context.getString(R.string.binding_label_down);
            case KEY_LEFT: return context.getString(R.string.binding_label_left);
            case KEY_ENTER: return context.getString(R.string.binding_label_enter);
            case KEY_ESC: return context.getString(R.string.binding_label_esc);
            case KEY_BKSP: return context.getString(R.string.binding_label_bksp);
            case KEY_DEL: return context.getString(R.string.binding_label_del);
            case KEY_INSERT: return context.getString(R.string.binding_label_insert);
            case KEY_TAB: return context.getString(R.string.binding_label_tab);
            case KEY_SPACE: return context.getString(R.string.binding_label_space);
            case KEY_CTRL_L: return context.getString(R.string.binding_label_l_ctrl);
            case KEY_CTRL_R: return context.getString(R.string.binding_label_r_ctrl);
            case KEY_SHIFT_L: return context.getString(R.string.binding_label_l_shift);
            case KEY_SHIFT_R: return context.getString(R.string.binding_label_r_shift);
            case KEY_ALT_L: return context.getString(R.string.binding_label_l_alt);
            case KEY_ALT_R: return context.getString(R.string.binding_label_r_alt);
            case KEY_HOME: return context.getString(R.string.binding_label_home);
            case KEY_PRTSCN: return context.getString(R.string.binding_label_prtscn);
            case KEY_PG_UP: return context.getString(R.string.binding_label_pg_up);
            case KEY_PG_DOWN: return context.getString(R.string.binding_label_pg_down);
            case KEY_END: return context.getString(R.string.binding_label_end);
            case KEY_CAPS_LOCK: return context.getString(R.string.binding_label_caps_lock);
            case KEY_NUM_LOCK: return context.getString(R.string.binding_label_num_lock);
            case KEY_KP_ADD: return context.getString(R.string.binding_label_kp_add);
            case KEY_KP_SUBTRACT: return context.getString(R.string.binding_label_kp_subtract);
            case KEY_PAUSE: return context.getString(R.string.binding_label_pause);
            case KEY_VOL_UP: return context.getString(R.string.binding_label_vol_up);
            case KEY_VOL_DOWN: return context.getString(R.string.binding_label_vol_down);
            case GAMEPAD_BUTTON_A: return context.getString(R.string.binding_label_button_a);
            case GAMEPAD_BUTTON_B: return context.getString(R.string.binding_label_button_b);
            case GAMEPAD_BUTTON_X: return context.getString(R.string.binding_label_button_x);
            case GAMEPAD_BUTTON_Y: return context.getString(R.string.binding_label_button_y);
            case GAMEPAD_BUTTON_L1: return context.getString(R.string.binding_label_l1);
            case GAMEPAD_BUTTON_R1: return context.getString(R.string.binding_label_r1);
            case GAMEPAD_BUTTON_SELECT: return context.getString(R.string.binding_label_select);
            case GAMEPAD_BUTTON_START: return context.getString(R.string.binding_label_start);
            case GAMEPAD_BUTTON_L3: return context.getString(R.string.binding_label_l3);
            case GAMEPAD_BUTTON_R3: return context.getString(R.string.binding_label_r3);
            case GAMEPAD_BUTTON_L2: return context.getString(R.string.binding_label_l2);
            case GAMEPAD_BUTTON_R2: return context.getString(R.string.binding_label_r2);
            case GAMEPAD_LEFT_THUMB_UP: return context.getString(R.string.binding_label_left_thumb_up);
            case GAMEPAD_LEFT_THUMB_RIGHT: return context.getString(R.string.binding_label_left_thumb_right);
            case GAMEPAD_LEFT_THUMB_DOWN: return context.getString(R.string.binding_label_left_thumb_down);
            case GAMEPAD_LEFT_THUMB_LEFT: return context.getString(R.string.binding_label_left_thumb_left);
            case GAMEPAD_RIGHT_THUMB_UP: return context.getString(R.string.binding_label_right_thumb_up);
            case GAMEPAD_RIGHT_THUMB_RIGHT: return context.getString(R.string.binding_label_right_thumb_right);
            case GAMEPAD_RIGHT_THUMB_DOWN: return context.getString(R.string.binding_label_right_thumb_down);
            case GAMEPAD_RIGHT_THUMB_LEFT: return context.getString(R.string.binding_label_right_thumb_left);
            case GAMEPAD_DPAD_UP: return context.getString(R.string.binding_label_dpad_up);
            case GAMEPAD_DPAD_RIGHT: return context.getString(R.string.binding_label_dpad_right);
            case GAMEPAD_DPAD_DOWN: return context.getString(R.string.binding_label_dpad_down);
            case GAMEPAD_DPAD_LEFT: return context.getString(R.string.binding_label_dpad_left);
            default: return toString();
        }
    }

    public static Binding fromString(String name) {
        switch (name) {
            case "KEY_CTRL":
                return Binding.KEY_CTRL_L;
            case "KEY_SHIFT":
                return Binding.KEY_SHIFT_L;
            case "KEY_ALT":
                return Binding.KEY_ALT_L;
            case "MOUSE_SWAP_BUTTONS":
                return Binding.MOUSE_SWAPL_R_BUTTONS;
            default:
                return valueOf(name);
        }
    }

    public Pointer.Button getPointerButton() {
        switch (this) {
            case MOUSE_LEFT_BUTTON:
                return Pointer.Button.BUTTON_LEFT;
            case MOUSE_MIDDLE_BUTTON:
                return Pointer.Button.BUTTON_MIDDLE;
            case MOUSE_RIGHT_BUTTON:
                return Pointer.Button.BUTTON_RIGHT;
            case MOUSE_SCROLL_UP:
                return Pointer.Button.BUTTON_SCROLL_UP;
            case MOUSE_SCROLL_DOWN:
                return Pointer.Button.BUTTON_SCROLL_DOWN;
            default:
                return null;
        }
    }

    public boolean isMouse() {
        return name().startsWith("MOUSE_");
    }

    public boolean isKeyboard() {
        return name().startsWith("KEY_") || this == NONE;
    }

    public boolean isGamepad() {
        return name().startsWith("GAMEPAD_");
    }

    public boolean isMouseMove() {
        return this == MOUSE_MOVE_UP || this == MOUSE_MOVE_RIGHT || this == MOUSE_MOVE_DOWN || this == MOUSE_MOVE_LEFT;
    }

    public static String[] mouseBindingLabels(Context context) {
        ArrayList<String> names = new ArrayList<>();
        for (Binding binding : values()) if (binding.isMouse()) names.add(binding.getDisplayName(context));
        return names.toArray(new String[0]);
    }

    public static String[] keyboardBindingLabels(Context context) {
        ArrayList<String> labels = new ArrayList<>();
        for (Binding binding : values()) if (binding.isKeyboard()) labels.add(binding.getDisplayName(context));
        return labels.toArray(new String[0]);
    }

    public static String[] gamepadBindingLabels(Context context) {
        ArrayList<String> names = new ArrayList<>();
        for (Binding binding : values()) if (binding.isGamepad()) names.add(binding.getDisplayName(context));
        return names.toArray(new String[0]);
    }

    public static Binding[] mouseBindingValues() {
        ArrayList<Binding> labels = new ArrayList<>();
        for (Binding binding : values()) if (binding.isMouse()) labels.add(binding);
        return labels.toArray(new Binding[0]);
    }

    public static Binding[] keyboardBindingValues() {
        ArrayList<Binding> values = new ArrayList<>();
        for (Binding binding : values()) if (binding.isKeyboard()) values.add(binding);
        return values.toArray(new Binding[0]);
    }

    public static Binding[] gamepadBindingValues() {
        ArrayList<Binding> labels = new ArrayList<>();
        for (Binding binding : values()) if (binding.isGamepad()) labels.add(binding);
        return labels.toArray(new Binding[0]);
    }
}
