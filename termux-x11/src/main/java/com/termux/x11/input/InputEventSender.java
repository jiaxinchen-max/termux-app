// Copyright 2016 The Chromium Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.termux.x11.input;

import static android.view.KeyEvent.*;
import static android.view.MotionEvent.*;
import static androidx.core.math.MathUtils.clamp;
import static com.termux.x11.input.InputStub.*;
import static java.nio.charset.StandardCharsets.UTF_8;

import android.graphics.PointF;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.List;
import java.util.Arrays;
import java.util.TreeSet;

/**
 * A set of functions to send users' activities, which are represented by Android classes, to
 * remote host machine. This class uses a {@link InputStub} to do the real injections.
 */
public final class InputEventSender {
    private static final int XI_TouchBegin = 18;
    private static final int XI_TouchUpdate = 19;
    private static final int XI_TouchEnd = 20;

    private final InputStub mInjector;

    /**
     * Last absolute pointer position sent. Button events carry coordinates too; receivers that
     * apply them as an absolute move (the Wayland/libinput bridge in KWin) would otherwise warp
     * the pointer to (0,0) on every click.
     */
    private float mLastX = 0, mLastY = 0;

    public boolean tapToMove = false;
    public boolean preferScancodes = false;
    public boolean pointerCapture = false;
    public boolean scaleTouchpad = false;
    public float capturedPointerSpeedFactor = 100;
    public boolean dexMetaKeyCapture = false;
    public boolean pauseKeyInterceptingWithEsc = false;
    public boolean stylusIsMouse = false;
    public boolean stylusButtonContactModifierMode = false;

    /** Set of pressed keys for which we've sent TextEvent. */
    private final TreeSet<Integer> mPressedTextKeys;
    private final TreeSet<Integer> mPressedKeys;
    private Runnable mReleaseCaptureCallback;

    public InputEventSender(InputStub injector) {
        if (injector == null)
            throw new NullPointerException();
        mInjector = injector;
        mPressedTextKeys = new TreeSet<>();
        mPressedKeys = new TreeSet<>();
    }

    public void setReleaseCaptureCallback(Runnable releaseCaptureCallback) {
        mReleaseCaptureCallback = releaseCaptureCallback;
    }

    private static final List<Integer> buttons = List.of(BUTTON_UNDEFINED, BUTTON_LEFT, BUTTON_MIDDLE, BUTTON_RIGHT);
    public void sendMouseEvent(PointF pos, int button, boolean down, boolean relative) {
        if (!buttons.contains(button))
            return;
        if (pos != null && !relative) {
            mLastX = (int) pos.x;
            mLastY = (int) pos.y;
        }
        mInjector.sendMouseEvent(pos != null ? (int) pos.x : 0, pos != null ? (int) pos.y : 0, button, down, relative);
    }

    public void sendStylusEvent(float x, float y, int pressure, int tiltX, int tiltY, int orientation, int buttons, boolean eraser, boolean mouse) {
        mInjector.sendStylusEvent(x, y, pressure, tiltX, tiltY, orientation, buttons, eraser, mouse);
        android.util.Log.d("STYLUS_EVENT", "transformed x " + x + " y " + y + " pressure " + pressure + " tiltX " + tiltX + " tiltY " + tiltY + " orientation " + orientation + " buttons " + buttons + " eraser " + eraser + " mouseMode " + mouse);
    }

    public void sendMouseDown(int button, boolean relative) {
        if (!buttons.contains(button))
            return;
        mInjector.sendMouseEvent(buttonX(relative), buttonY(relative), button, true, relative);
    }

    private float buttonX(boolean relative) { return relative ? 0 : mLastX; }
    private float buttonY(boolean relative) { return relative ? 0 : mLastY; }

    public void sendMouseUp(int button, boolean relative) {
        if (!buttons.contains(button))
            return;
        mInjector.sendMouseEvent(buttonX(relative), buttonY(relative), button, false, relative);
    }

    public void sendMouseClick(int button, boolean relative) {
        if (!buttons.contains(button))
            return;
        mInjector.sendMouseEvent(buttonX(relative), buttonY(relative), button, true, relative);
        mInjector.sendMouseEvent(buttonX(relative), buttonY(relative), button, false, relative);
    }

    public void sendCursorMove(float x, float y, boolean relative) {
        if (!relative) {
            mLastX = x;
            mLastY = y;
        }
        mInjector.sendMouseEvent(x, y, BUTTON_UNDEFINED, false, relative);
    }

    /** Gesture distances are desktop pixels; the bridge uses 120 units per 15 pixels. */
    public void sendMouseScrollPixels(float distanceX, float distanceY) {
        sendMouseWheelEvent(distanceX * 120f / 15f, distanceY * 120f / 15f);
    }

    /** Hardware wheel distances are already expressed in v120 units. */
    public void sendMouseWheelEvent(float distanceX, float distanceY) {
        mInjector.sendMouseWheelEvent(distanceX, distanceY);
    }

    // Android pointer IDs range from 0 to 31 independently of the number of fingers.
    private final boolean[] pointers = new boolean[32];
    private final boolean[] presentPointers = new boolean[32];
    private final int[] pointerX = new int[32];
    private final int[] pointerY = new int[32];

    /** Ends every contact when Android cancels a gesture or the direct-touch strategy changes. */
    public void cancelActiveTouchEvents() {
        for (int id = 0; id < pointers.length; id++) {
            if (pointers[id]) sendTouchContactEvent(XI_TouchEnd, id, pointerX[id], pointerY[id]);
        }
    }

    // Coordinates here already belong to the desktop. Only live contacts may update or end.
    void sendTouchContactEvent(int action, int id, int x, int y) {
        if (id < 0 || id >= pointers.length) return;
        if (action == XI_TouchBegin) {
            if (pointers[id]) sendTouchContactEvent(XI_TouchEnd, id, pointerX[id], pointerY[id]);
            pointers[id] = true;
        } else if (!pointers[id]) {
            return;
        }
        pointerX[id] = x;
        pointerY[id] = y;
        mInjector.sendTouchEvent(action, id, x, y);
        if (action == XI_TouchEnd) pointers[id] = false;
    }
    /**
     * Extracts the touch point data from a MotionEvent, converts each point into a marshallable
     * object and passes the set of points to the JNI layer to be transmitted to the remote host.
     *
     * @param event The event to send to the remote host for injection.  NOTE: This object must be
     *              updated to represent the remote machine's coordinate system before calling this
     *              function.
     */
    public void sendTouchEvent(MotionEvent event, RenderData renderData) {
        int action = event.getActionMasked();
        if (action == ACTION_CANCEL || action == ACTION_HOVER_EXIT) {
            cancelActiveTouchEvents();
            return;
        }
        if (action == MotionEvent.ACTION_DOWN) cancelActiveTouchEvents();

        if (action == ACTION_MOVE || action == ACTION_HOVER_MOVE || action == ACTION_HOVER_ENTER) {
            Arrays.fill(presentPointers, false);
            for (int p = 0; p < event.getPointerCount(); p++) {
                int id = event.getPointerId(p);
                if (id < 0 || id >= pointers.length) continue;
                presentPointers[id] = true;
                int x = clamp((int) renderData.mapScreenX(event.getX(p)), 0, renderData.screenWidth);
                int y = clamp((int) renderData.mapScreenY(event.getY(p)), 0, renderData.screenHeight);
                sendTouchContactEvent(XI_TouchUpdate, id, x, y);
            }
            // Recover omitted pointer-up events without inventing ends for inactive IDs.
            for (int id = 0; id < pointers.length; id++) {
                if (pointers[id] && !presentPointers[id])
                    sendTouchContactEvent(XI_TouchEnd, id, pointerX[id], pointerY[id]);
            }
        } else if (action == MotionEvent.ACTION_DOWN || action == ACTION_POINTER_DOWN
            || action == MotionEvent.ACTION_UP || action == ACTION_POINTER_UP) {
            int index = event.getActionIndex();
            int id = event.getPointerId(index);
            int x = clamp((int) renderData.mapScreenX(event.getX(index)), 0, renderData.screenWidth);
            int y = clamp((int) renderData.mapScreenY(event.getY(index)), 0, renderData.screenHeight);
            if (action == MotionEvent.ACTION_DOWN || action == ACTION_POINTER_DOWN) {
                sendTouchContactEvent(XI_TouchBegin, id, x, y);
            } else {
                sendTouchContactEvent(XI_TouchUpdate, id, x, y);
                sendTouchContactEvent(XI_TouchEnd, id, x, y);
                if (action == MotionEvent.ACTION_UP) cancelActiveTouchEvents();
            }
        }
    }

    /**
     * Converts the {@link KeyEvent} into low-level events and sends them to the host as either
     * key-events or text-events. This contains some logic for handling some special keys, and
     * avoids sending a key-up event for a key that was previously injected as a text-event.
     */
    public boolean sendKeyEvent(KeyEvent e) {
        int keyCode = e.getKeyCode();
        boolean pressed = e.getAction() == KeyEvent.ACTION_DOWN;

        if ((e.getFlags() & KeyEvent.FLAG_CANCELED) == KeyEvent.FLAG_CANCELED) {
            android.util.Log.d("KeyEvent", "We've got key event with FLAG_CANCELED, it will not be consumed. Details: " + e);
            return true;
        }

        // Events received from software keyboards generate TextEvent in two
        // cases:
        //   1. This is an ACTION_MULTIPLE event.
        //   2. Ctrl, Alt and Meta are not pressed.
        // This ensures that on-screen keyboard always injects input that
        // correspond to what user sees on the screen, while physical keyboard
        // acts as if it is connected to the remote host.
        if (e.getAction() == ACTION_MULTIPLE) {
            if (e.getCharacters() != null)
                mInjector.sendTextEvent(e.getCharacters().getBytes(UTF_8));
            else if (e.getUnicodeChar() != 0)
                mInjector.sendTextEvent(String.valueOf((char)e.getUnicodeChar()).getBytes(UTF_8));
            return true;
        }

        boolean no_modifiers = (!e.isAltPressed() && !e.isCtrlPressed() && !e.isMetaPressed())
            || ((e.getMetaState() & META_ALT_RIGHT_ON) != 0 && (e.getCharacters() != null || e.getUnicodeChar() != 0)); // For layouts with AltGr
        // For Enter getUnicodeChar() returns 10 (line feed), but we still
        // want to send it as KeyEvent.
        char unicode = keyCode != KEYCODE_ENTER ? (char) e.getUnicodeChar() : 0;
        int scancode = (preferScancodes || !no_modifiers) ? e.getScanCode(): 0;

        if (!preferScancodes) {
            if (pressed && unicode != 0 && no_modifiers) {
                mPressedTextKeys.add(keyCode);
                if ((e.getMetaState() & META_ALT_RIGHT_ON) != 0)
                    mInjector.sendKeyEvent(0, KEYCODE_ALT_RIGHT, false); // For layouts with AltGr

                mInjector.sendTextEvent(String.valueOf(unicode).getBytes(UTF_8));

                if ((e.getMetaState() & META_ALT_RIGHT_ON) != 0)
                    mInjector.sendKeyEvent(0, KEYCODE_ALT_RIGHT, true); // For layouts with AltGr
                return true;
            }

            if (!pressed && mPressedTextKeys.contains(keyCode)) {
                mPressedTextKeys.remove(keyCode);
                return true;
            }
        }

        // KEYCODE_AT, KEYCODE_POUND, KEYCODE_STAR and KEYCODE_PLUS are
        // deprecated, but they still need to be here for older devices and
        // third-party keyboards that may still generate these events. See
        // https://source.android.com/devices/input/keyboard-devices.html#legacy-unsupported-keys
        char[][] chars = {
            { KEYCODE_AT, '@', KEYCODE_2 },
            { KEYCODE_POUND, '#', KEYCODE_3 },
            { KEYCODE_STAR, '*', KEYCODE_8 },
            { KEYCODE_PLUS, '+', KEYCODE_EQUALS }
        };

        for (char[] i: chars) {
            if (e.getKeyCode() != i[0])
                continue;

            if ((e.getCharacters() != null && String.valueOf(i[1]).contentEquals(e.getCharacters()))
                || e.getUnicodeChar() == i[1]) {
                mInjector.sendKeyEvent(0, KEYCODE_SHIFT_LEFT, pressed);
                mInjector.sendKeyEvent(0, i[2], pressed);
                return true;
            }
        }

        // Ignoring Android's autorepeat.
        // But some weird IMEs (or firmwares) send first event with repeatCount=1 (not 0)
        // Probably related to preceding event with FLAG_CANCELLED flag
        if (e.getRepeatCount() > 0 && mPressedKeys.contains(keyCode))
            return true;

        if (pressed)
            mPressedKeys.add(keyCode);
        else
            mPressedKeys.remove(keyCode);

        if (keyCode == KEYCODE_ESCAPE && !pressed && e.hasNoModifiers() && mReleaseCaptureCallback != null)
            mReleaseCaptureCallback.run();

        // We try to send all other key codes to the host directly.
        return mInjector.sendKeyEvent(scancode, keyCode, pressed);
    }
}
