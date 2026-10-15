package com.termux.x11.controller.widget;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.termux.x11.LorieView;

public class TouchpadView extends FrameLayout {
    public static final byte TOUCHPAD_MODE_V1 = 0;
    public static final byte TOUCHPAD_MODE_V2 = 1;
    public static final byte TOUCHPAD_MODE_V3 = 2;
    public static final byte MAX_TAP_TRAVEL_DISTANCE = 10;
    public static final short MAX_TAP_MILLISECONDS = 200;
    public static final float CURSOR_ACCELERATION = 1.5f;
    public static final byte CURSOR_ACCELERATION_THRESHOLD = 6;

    private final Context context;
    private final LorieView xServer;
    private final boolean capturePointerOnExternalMouse;
    private byte touchpadMode = TOUCHPAD_MODE_V1;
    private View impl;

    private float sensitivity = 1.0f;
    private boolean pointerButtonLeftEnabled = true;
    private boolean pointerButtonRightEnabled = true;
    private boolean moveCursorToTouchpoint = false;
    private boolean twoFingersDrag = true;
    private boolean twoFingersRightClick = true;
    private boolean longPressRightClick = true;
    private boolean pinchZoomEnabled = false;
    private boolean shortDragEnabled = false;
    private boolean twoFingersScroll = true;
    private Runnable fourFingersTapCallback;

    public TouchpadView(Context context, LorieView xServer, boolean capturePointerOnExternalMouse) {
        super(context);
        this.context = context;
        this.xServer = xServer;
        this.capturePointerOnExternalMouse = capturePointerOnExternalMouse;
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        createImpl();
    }

    private void createImpl() {
        if (impl != null) removeView(impl);

        if (touchpadMode == TOUCHPAD_MODE_V2) {
            impl = new TouchpadViewV2(context, xServer, capturePointerOnExternalMouse);
        } else if (touchpadMode == TOUCHPAD_MODE_V3) {
            impl = new TouchpadViewV3(context, xServer, capturePointerOnExternalMouse);
        } else {
            impl = new TouchpadViewV1(context, xServer, capturePointerOnExternalMouse);
        }

        addView(impl);
        applySettings();
    }

    private void applySettings() {
        if (impl instanceof TouchpadViewV1) {
            TouchpadViewV1 v1 = (TouchpadViewV1) impl;
            v1.setSensitivity(sensitivity);
            v1.setPointerButtonLeftEnabled(pointerButtonLeftEnabled);
            v1.setPointerButtonRightEnabled(pointerButtonRightEnabled);
            v1.setMoveCursorToTouchpoint(moveCursorToTouchpoint);
            v1.setFourFingersTapCallback(fourFingersTapCallback);
            v1.setTwoFingersDrag(twoFingersDrag);
            v1.setTwoFingersRightClick(twoFingersRightClick);
            v1.setLongPressRightClick(longPressRightClick);
            v1.setPinchZoomEnabled(pinchZoomEnabled);
            v1.setShortDragEnabled(shortDragEnabled);
            v1.setTwoFingersScroll(twoFingersScroll);
            v1.setEnabled(isEnabled());
        } else if (impl instanceof TouchpadViewV2) {
            TouchpadViewV2 v2 = (TouchpadViewV2) impl;
            v2.setSensitivity(sensitivity);
            v2.setPointerButtonLeftEnabled(pointerButtonLeftEnabled);
            v2.setPointerButtonRightEnabled(pointerButtonRightEnabled);
            v2.setMoveCursorToTouchpoint(moveCursorToTouchpoint);
            v2.setFourFingersTapCallback(fourFingersTapCallback);
            v2.setTwoFingersDrag(twoFingersDrag);
            v2.setTwoFingersRightClick(twoFingersRightClick);
            v2.setLongPressRightClick(longPressRightClick);
            v2.setPinchZoomEnabled(pinchZoomEnabled);
            v2.setShortDragEnabled(shortDragEnabled);
            v2.setTwoFingersScroll(twoFingersScroll);
            v2.setEnabled(isEnabled());
        } else if (impl instanceof TouchpadViewV3) {
            TouchpadViewV3 v3 = (TouchpadViewV3) impl;
            v3.setSensitivity(sensitivity);
            v3.setPointerButtonLeftEnabled(pointerButtonLeftEnabled);
            v3.setPointerButtonRightEnabled(pointerButtonRightEnabled);
            v3.setMoveCursorToTouchpoint(moveCursorToTouchpoint);
            v3.setFourFingersTapCallback(fourFingersTapCallback);
            v3.setTwoFingersDrag(twoFingersDrag);
            v3.setTwoFingersRightClick(twoFingersRightClick);
            v3.setLongPressRightClick(longPressRightClick);
            v3.setPinchZoomEnabled(pinchZoomEnabled);
            v3.setShortDragEnabled(shortDragEnabled);
            v3.setTwoFingersScroll(twoFingersScroll);
            v3.setEnabled(isEnabled());
        }
    }

    public void setTouchpadMode(byte touchpadMode) {
        if (this.touchpadMode != touchpadMode) {
            this.touchpadMode = touchpadMode;
            createImpl();
        }
    }

    public byte getTouchpadMode() {
        return touchpadMode;
    }

    public void toggleFullscreen() {
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).toggleFullscreen();
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).toggleFullscreen();
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).toggleFullscreen();
        }
    }

    public void setSensitivity(float sensitivity) {
        this.sensitivity = sensitivity;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setSensitivity(sensitivity);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setSensitivity(sensitivity);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setSensitivity(sensitivity);
        }
    }

    public boolean isPointerButtonLeftEnabled() {
        return pointerButtonLeftEnabled;
    }

    public void setPointerButtonLeftEnabled(boolean pointerButtonLeftEnabled) {
        this.pointerButtonLeftEnabled = pointerButtonLeftEnabled;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setPointerButtonLeftEnabled(pointerButtonLeftEnabled);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setPointerButtonLeftEnabled(pointerButtonLeftEnabled);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setPointerButtonLeftEnabled(pointerButtonLeftEnabled);
        }
    }

    public boolean isPointerButtonRightEnabled() {
        return pointerButtonRightEnabled;
    }

    public void setPointerButtonRightEnabled(boolean pointerButtonRightEnabled) {
        this.pointerButtonRightEnabled = pointerButtonRightEnabled;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setPointerButtonRightEnabled(pointerButtonRightEnabled);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setPointerButtonRightEnabled(pointerButtonRightEnabled);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setPointerButtonRightEnabled(pointerButtonRightEnabled);
        }
    }

    public void setFourFingersTapCallback(Runnable fourFingersTapCallback) {
        this.fourFingersTapCallback = fourFingersTapCallback;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setFourFingersTapCallback(fourFingersTapCallback);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setFourFingersTapCallback(fourFingersTapCallback);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setFourFingersTapCallback(fourFingersTapCallback);
        }
    }

    public boolean isMoveCursorToTouchpoint() {
        return moveCursorToTouchpoint;
    }

    public void setMoveCursorToTouchpoint(boolean moveCursorToTouchpoint) {
        this.moveCursorToTouchpoint = moveCursorToTouchpoint;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setMoveCursorToTouchpoint(moveCursorToTouchpoint);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setMoveCursorToTouchpoint(moveCursorToTouchpoint);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setMoveCursorToTouchpoint(moveCursorToTouchpoint);
        }
    }

    public boolean isTwoFingersDrag() {
        return twoFingersDrag;
    }

    public void setTwoFingersDrag(boolean twoFingersDrag) {
        this.twoFingersDrag = twoFingersDrag;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setTwoFingersDrag(twoFingersDrag);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setTwoFingersDrag(twoFingersDrag);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setTwoFingersDrag(twoFingersDrag);
        }
    }

    public boolean isTwoFingersRightClick() {
        return twoFingersRightClick;
    }

    public void setTwoFingersRightClick(boolean twoFingersRightClick) {
        this.twoFingersRightClick = twoFingersRightClick;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setTwoFingersRightClick(twoFingersRightClick);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setTwoFingersRightClick(twoFingersRightClick);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setTwoFingersRightClick(twoFingersRightClick);
        }
    }

    public boolean isLongPressRightClick() {
        return longPressRightClick;
    }

    public void setLongPressRightClick(boolean longPressRightClick) {
        this.longPressRightClick = longPressRightClick;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setLongPressRightClick(longPressRightClick);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setLongPressRightClick(longPressRightClick);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setLongPressRightClick(longPressRightClick);
        }
    }

    public boolean isPinchZoomEnabled() {
        return pinchZoomEnabled;
    }

    public void setPinchZoomEnabled(boolean pinchZoomEnabled) {
        this.pinchZoomEnabled = pinchZoomEnabled;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setPinchZoomEnabled(pinchZoomEnabled);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setPinchZoomEnabled(pinchZoomEnabled);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setPinchZoomEnabled(pinchZoomEnabled);
        }
    }

    public boolean isShortDragEnabled() {
        return shortDragEnabled;
    }

    public void setShortDragEnabled(boolean shortDragEnabled) {
        this.shortDragEnabled = shortDragEnabled;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setShortDragEnabled(shortDragEnabled);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setShortDragEnabled(shortDragEnabled);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setShortDragEnabled(shortDragEnabled);
        }
    }

    public boolean isTwoFingersScroll() {
        return twoFingersScroll;
    }

    public void setTwoFingersScroll(boolean twoFingersScroll) {
        this.twoFingersScroll = twoFingersScroll;
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setTwoFingersScroll(twoFingersScroll);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setTwoFingersScroll(twoFingersScroll);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setTwoFingersScroll(twoFingersScroll);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return impl.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return false;
    }

    public boolean onExternalMouseEvent(MotionEvent event) {
        if (impl instanceof TouchpadViewV1) {
            return ((TouchpadViewV1) impl).onExternalMouseEvent(event);
        } else if (impl instanceof TouchpadViewV2) {
            return ((TouchpadViewV2) impl).onExternalMouseEvent(event);
        } else if (impl instanceof TouchpadViewV3) {
            return ((TouchpadViewV3) impl).onExternalMouseEvent(event);
        }
        return false;
    }

    public boolean onCapturedPointer(View view, MotionEvent event) {
        if (impl instanceof TouchpadViewV1) {
            return ((TouchpadViewV1) impl).onCapturedPointer(view, event);
        } else if (impl instanceof TouchpadViewV2) {
            return ((TouchpadViewV2) impl).onCapturedPointer(view, event);
        } else if (impl instanceof TouchpadViewV3) {
            return ((TouchpadViewV3) impl).onCapturedPointer(view, event);
        }
        return false;
    }

    public float[] computeDeltaPoint(float lastX, float lastY, float x, float y) {
        if (impl instanceof TouchpadViewV1) {
            return ((TouchpadViewV1) impl).computeDeltaPoint(lastX, lastY, x, y);
        } else if (impl instanceof TouchpadViewV2) {
            return ((TouchpadViewV2) impl).computeDeltaPoint(lastX, lastY, x, y);
        } else if (impl instanceof TouchpadViewV3) {
            return ((TouchpadViewV3) impl).computeDeltaPoint(lastX, lastY, x, y);
        }
        return new float[]{0, 0};
    }

    public void mouseMove(float x, float y, int action) {
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).mouseMove(x, y, action);
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).mouseMove(x, y, action);
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).mouseMove(x, y, action);
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        impl.setEnabled(enabled);
    }

    @Override
    public boolean isEnabled() {
        return impl != null && impl.isEnabled();
    }

    public void setSwapMouseButtons() {
        if (impl instanceof TouchpadViewV1) {
            ((TouchpadViewV1) impl).setSwapMouseButtons();
        } else if (impl instanceof TouchpadViewV2) {
            ((TouchpadViewV2) impl).setSwapMouseButtons();
        } else if (impl instanceof TouchpadViewV3) {
            ((TouchpadViewV3) impl).setSwapMouseButtons();
        }
    }

    public boolean isSwapMouseButtons() {
        if (impl instanceof TouchpadViewV1) {
            return ((TouchpadViewV1) impl).isSwapMouseButtons();
        } else if (impl instanceof TouchpadViewV2) {
            return ((TouchpadViewV2) impl).isSwapMouseButtons();
        } else if (impl instanceof TouchpadViewV3) {
            return ((TouchpadViewV3) impl).isSwapMouseButtons();
        }
        return false;
    }

    @Override
    public void requestPointerCapture() {
        impl.requestPointerCapture();
    }
}